const fs = require('fs')
const express = require('express')
require('dotenv').config()
const { TelegramBridge } = require('./telegram')

const SESSION_FILE = process.env.SESSION_FILE || '/data/session'
function loadSession() {
  if (process.env.TELEGRAM_SESSION) return process.env.TELEGRAM_SESSION
  try { return fs.readFileSync(SESSION_FILE, 'utf8').trim() } catch { return '' }
}

const apiId = parseInt(process.env.API_ID || '0', 10)
const apiHash = process.env.API_HASH || ''
const session = loadSession()
const apiKey = process.env.BRIDGE_API_KEY || ''
const port = parseInt(process.env.PORT || '8787', 10)

const configured = Boolean(apiId && apiHash)
if (!configured) {
  console.warn('API_ID/API_HASH not set yet. /health works; data endpoints return 503 until you set them and run login.')
}
const bridge = configured ? new TelegramBridge(apiId, apiHash, session) : null
const app = express()
app.use(express.json({ limit: '30mb' }))

function requireBridge(res) {
  if (!bridge) {
    res.status(503).json({ error: 'Bridge not configured: set API_ID/API_HASH and run login.' })
    return false
  }
  return true
}

// Same auth style as Evolution: an "apikey" header. /health is also protected.
app.use((req, res, next) => {
  if (apiKey && req.get('apikey') !== apiKey) return res.status(401).json({ error: 'unauthorized' })
  next()
})

const ok = (res, data) => res.json(data)
const fail = (res, e) => res.status(500).json({ error: String(e?.message || e) })

app.get('/health', async (_req, res) => {
  if (!bridge) return res.json({ ok: true, authorized: false, error: 'API_ID/API_HASH not set' })
  try {
    res.json({ ok: true, authorized: await bridge.authorized() })
  } catch (e) {
    res.json({ ok: true, authorized: false, error: String(e?.message || e) })
  }
})

app.post('/chats', async (req, res) => {
  if (!requireBridge(res)) return
  try { ok(res, await bridge.listChats(req.body.limit || 20)) } catch (e) { fail(res, e) }
})

app.post('/messages', async (req, res) => {
  if (!requireBridge(res)) return
  try { ok(res, await bridge.listMessages(req.body.chatId, req.body.limit || 20)) } catch (e) { fail(res, e) }
})

app.post('/sendText', async (req, res) => {
  if (!requireBridge(res)) return
  try {
    await bridge.sendText(req.body.chatId, req.body.text || '', req.body.replyToId || '')
    ok(res, { ok: true })
  } catch (e) { fail(res, e) }
})

app.post('/sendVoice', async (req, res) => {
  if (!requireBridge(res)) return
  try {
    const buf = Buffer.from(req.body.audioBase64 || '', 'base64')
    await bridge.sendVoice(req.body.chatId, buf, req.body.durationSec || 1, req.body.replyToId || '')
    ok(res, { ok: true })
  } catch (e) { fail(res, e) }
})

app.post('/sendReaction', async (req, res) => {
  if (!requireBridge(res)) return
  try {
    await bridge.sendReaction(req.body.chatId, req.body.messageId, req.body.emoji || '')
    ok(res, { ok: true })
  } catch (e) { fail(res, e) }
})

app.post('/markRead', async (req, res) => {
  if (!requireBridge(res)) return
  try {
    await bridge.markRead(req.body.chatId)
    ok(res, { ok: true })
  } catch (e) { fail(res, e) }
})

app.post('/media', async (req, res) => {
  if (!requireBridge(res)) return
  try {
    const bytes = await bridge.fetchMedia(req.body.chatId, req.body.messageId)
    if (!bytes) return ok(res, { base64: '' })
    ok(res, { base64: bytes.toString('base64') })
  } catch (e) { fail(res, e) }
})

app.listen(port, () => console.log(`Rokid Inbox Telegram bridge listening on :${port}`))
