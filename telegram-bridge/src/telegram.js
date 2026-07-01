const { TelegramClient, Api } = require('telegram')
const { StringSession } = require('telegram/sessions')
const { CustomFile } = require('telegram/client/uploads')

/**
 * Thin MTProto wrapper (GramJS) exposing exactly what the Rokid Inbox phone app
 * needs. Mirrors the channel logic of even-inbox `src/channels/telegram.ts`, but
 * runs server-side so the phone only speaks HTTP (see server.js).
 */
class TelegramBridge {
  constructor(apiId, apiHash, session) {
    this.client = new TelegramClient(new StringSession(session || ''), apiId, apiHash, {
      connectionRetries: 5,
    })
    this.dialogCache = new Map()
  }

  async connect() {
    if (this.client.connected) return
    await this.client.connect()
  }

  async authorized() {
    await this.connect()
    return this.client.checkAuthorization()
  }

  async listChats(limit = 20) {
    await this.connect()
    const dialogs = await this.client.getDialogs({ limit: Math.max(limit, 100) })
    this.dialogCache.clear()
    for (const d of dialogs) {
      const id = d.entity?.id?.toString()
      if (id) this.dialogCache.set(id, d)
    }
    return dialogs.slice(0, limit).map((d) => ({
      id: d.entity?.id?.toString() ?? '',
      name: d.name || d.title || 'Telegram',
      type: this.chatType(d),
      unreadCount: d.unreadCount ?? 0,
      lastMessageDate: d.date ? new Date(d.date * 1000).toISOString() : null,
      lastMessagePreview: this.dialogPreview(d),
      isPinned: Boolean(d.pinned),
    }))
  }

  async listMessages(chatId, limit = 20) {
    await this.connect()
    const dialog = await this.resolveDialog(chatId)
    if (!dialog) throw new Error('Chat not found')
    const fetched = await this.client.getMessages(dialog.entity, { limit })
    return fetched.map((m) => this.mapMessage(m))
  }

  async sendText(chatId, text, replyToId) {
    await this.connect()
    const dialog = await this.resolveDialog(chatId)
    if (!dialog) throw new Error('Chat not found')
    const opts = { message: text }
    if (replyToId) opts.replyTo = parseInt(replyToId, 10)
    await this.client.sendMessage(dialog.entity, opts)
  }

  async sendVoice(chatId, buffer, durationSec, replyToId) {
    await this.connect()
    const dialog = await this.resolveDialog(chatId)
    if (!dialog) throw new Error('Chat not found')
    const file = new CustomFile('voice.ogg', buffer.length, '', buffer)
    const opts = {
      file,
      voiceNote: true,
      attributes: [
        new Api.DocumentAttributeAudio({
          duration: Math.max(1, Math.round(durationSec || 1)),
          voice: true,
        }),
      ],
    }
    if (replyToId) opts.replyTo = parseInt(replyToId, 10)
    await this.client.sendFile(dialog.entity, opts)
  }

  async sendReaction(chatId, messageId, emoji) {
    await this.connect()
    const dialog = await this.resolveDialog(chatId)
    if (!dialog) throw new Error('Chat not found')
    await this.client.invoke(
      new Api.messages.SendReaction({
        peer: dialog.entity,
        msgId: parseInt(messageId, 10),
        reaction: [new Api.ReactionEmoji({ emoticon: emoji })],
      }),
    )
  }

  async markRead(chatId) {
    await this.connect()
    const dialog = await this.resolveDialog(chatId)
    if (!dialog) return
    await this.client.markAsRead(dialog.entity)
  }

  async fetchMedia(chatId, messageId) {
    await this.connect()
    const dialog = await this.resolveDialog(chatId)
    if (!dialog) return null
    const id = parseInt(messageId, 10)
    if (!Number.isFinite(id)) return null
    const fetched = await this.client.getMessages(dialog.entity, { ids: [id] })
    const msg = fetched?.[0]
    if (!msg || !msg.media) return null
    const buf = await this.client.downloadMedia(msg)
    if (!buf || typeof buf === 'string') return null
    return Buffer.from(buf)
  }

  /* ---------------- helpers ---------------- */

  async resolveDialog(chatId) {
    if (this.dialogCache.has(chatId)) return this.dialogCache.get(chatId)
    await this.listChats(200)
    return this.dialogCache.get(chatId) ?? null
  }

  chatType(d) {
    if (d.isChannel) return 'channel'
    if (d.isGroup) return 'group'
    return 'user'
  }

  dialogPreview(d) {
    const m = d.message
    if (!m) return ''
    const text = m.message || (m.media ? this.mediaTag(m) || '' : '')
    return String(text).replace(/\s+/g, ' ').trim().slice(0, 120)
  }

  mapMessage(m) {
    return {
      id: String(m.id),
      text: m.message || '',
      media: this.mediaTag(m),
      date: m.date ? new Date(m.date * 1000).toISOString() : null,
      isOutgoing: Boolean(m.out),
      senderName: this.senderName(m.sender),
      durationSec: this.audioDuration(m),
      fileName: this.fileName(m),
    }
  }

  fileName(m) {
    const attrs = m.media?.document?.attributes || []
    const a = attrs.find((x) => x instanceof Api.DocumentAttributeFilename)
    return a?.fileName || ''
  }

  mediaTag(m) {
    const media = m.media
    if (!media) return null
    if (media instanceof Api.MessageMediaPhoto) return '[photo]'
    if (media instanceof Api.MessageMediaDocument) {
      const doc = media.document
      const mime = doc?.mimeType || ''
      const attrs = doc?.attributes || []
      const isVoice = attrs.some((a) => a instanceof Api.DocumentAttributeAudio && a.voice)
      const isAudio = attrs.some((a) => a instanceof Api.DocumentAttributeAudio)
      const isVideo = attrs.some((a) => a instanceof Api.DocumentAttributeVideo)
      const isSticker = attrs.some((a) => a instanceof Api.DocumentAttributeSticker)
      if (isVoice) return '[voice]'
      if (isVideo || mime.startsWith('video')) return '[video]'
      if (isSticker) return '[sticker]'
      if (isAudio || mime.startsWith('audio')) return '[audio]'
      return '[file]'
    }
    if (media instanceof Api.MessageMediaWebPage) return '[link]'
    if (media instanceof Api.MessageMediaPoll) return '[poll]'
    return '[media]'
  }

  audioDuration(m) {
    const attrs = m.media?.document?.attributes || []
    const a = attrs.find((x) => x instanceof Api.DocumentAttributeAudio)
    return a?.duration || 0
  }

  senderName(s) {
    if (!s) return ''
    if (s.title) return s.title
    const parts = [s.firstName, s.lastName].filter(Boolean)
    if (parts.length) return parts.join(' ')
    if (s.username) return '@' + s.username
    return ''
  }
}

module.exports = { TelegramBridge }
