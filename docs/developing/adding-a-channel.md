# Adding a new channel

A "channel" is a message source (WhatsApp, Telegram, Gmail, GitHub…). Channels
live **entirely on the phone** — the glasses are channel-agnostic and just render
the unified inbox. To add one you implement a `ChannelService`, wire it into the
config/graph, add a settings form, and (for display) a logo.

If your source has no on-device API (e.g. needs Node/Python or heavy native
code), run it as a small self-hosted HTTP **bridge** and make your
`ChannelService` an HTTP client — exactly like [Telegram](../channels/telegram.md)
does with `telegram-bridge/`, or how WhatsApp uses Evolution API.

## The contract

`android-phone/.../channels/ChannelService.kt`:

```kotlin
interface ChannelService {
    val kind: ChannelKind
    val boxId: String
    val canSend: Boolean            // false = read-only (glasses hide the reply menu)
    val canReact: Boolean get() = false

    // Messages are returned oldest-first (chronological).
    suspend fun listChats(limit: Int = 20): List<Chat>
    suspend fun listMessages(chatId: String, limit: Int = 20): List<Message>

    suspend fun sendText(chatId: String, text: String, replyToId: String = "", replyFromMe: Boolean = false)
    suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String = "", replyFromMe: Boolean = false)
    suspend fun sendReaction(chatId: String, messageId: String, emoji: String, fromMe: Boolean) { throw UnsupportedOperationException() }
    suspend fun markAsRead(chatId: String, messages: List<Message>)

    suspend fun fetchMedia(chatId: String, message: Message): ByteArray? = null  // image/audio bytes
    suspend fun ping()              // credential/connectivity check for the settings screen
    suspend fun disconnect() {}
}
```

Data types (`shared-contracts/.../InboxContracts.kt`): `Chat`, `Message`,
`ChannelKind`, `ChatType`. Produce `Chat` with `channel`/`boxId` set; leave
`boxLabel` empty (the phone computes it). Use media tags `[photo]`, `[voice]`,
`[audio]`, `[video]`, `[file]` so the glasses know how to render.

## Steps

### 1. Add the channel kind (if new)

In `shared-contracts/.../InboxContracts.kt`:

```kotlin
enum class ChannelKind { WHATSAPP, TELEGRAM, GMAIL, GITHUB, SLACK }  // + SLACK
```

### 2. Implement the service

Create `android-phone/.../channels/SlackService.kt`. For REST sources, reuse the
shared HTTP helpers in `channels/Http.kt` (`Http.client`, `Http.parse`, and the
`obj()/str()/arr()/optObj()/optArr()/intOrNull()` extensions). See
`GitHubService`/`GmailService` for read-only examples, `WhatsAppService`/
`TelegramService` for full send/react/media examples.

```kotlin
class SlackService(
    override val boxId: String,
    private val token: String,
) : ChannelService {
    override val kind = ChannelKind.SLACK
    override val canSend = true
    override val canReact = true
    override suspend fun ping() { /* GET auth.test, throw on failure */ }
    override suspend fun listChats(limit: Int): List<Chat> { /* ... */ TODO() }
    override suspend fun listMessages(chatId: String, limit: Int): List<Message> { /* oldest-first */ TODO() }
    override suspend fun sendText(chatId: String, text: String, replyToId: String, replyFromMe: Boolean) { /* ... */ }
    override suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String, replyFromMe: Boolean) { /* ... */ }
    override suspend fun markAsRead(chatId: String, messages: List<Message>) { /* ... */ }
}
```

### 3. Instantiate it in the graph

In `InboxGraph.instantiateServices()`, add a `when (box.kind)` branch mapping the
stored config to your service:

```kotlin
ChannelKind.SLACK -> SlackService(boxId = box.id, token = box.config["token"].orEmpty())
```

Config is a `Map<String,String>` stored per box in `InboxConfigStore`
(`EncryptedSharedPreferences`).

### 4. Add a settings form

In `InboxSettingsActivity`, add a `+ Add Slack` button in `rebuild()` and an
`addSlack()` that collects fields, then calls `validateAndSave(kind, name, cfg) { probe }`.
`validateAndSave` builds a probe instance and calls `ping()` on a background
thread before persisting:

```kotlin
private fun addSlack() {
    val token = editText("Bot/User token", "", password = true)
    formDialog("Slack", listOf("Token" to token)) {
        val cfg = mapOf("token" to token.text.toString().trim())
        validateAndSave(ChannelKind.SLACK, "Slack", cfg) { SlackService("probe", cfg["token"]!!) }
    }
}
```

### 5. Add the logo (glasses)

The glasses show a per-channel logo. Rasterize a white icon PNG into
`android-glasses/.../res/drawable-nodpi/ic_ch_slack.png` (e.g. from
[simple-icons](https://simpleicons.org) via `rsvg-convert`; keep it **white** —
black is invisible on the see-through display, the app tints it amber), then map
it in `InboxGlassesActivity`:

```kotlin
private fun iconFor(channel: ChannelKind): Int = when (channel) {
    // ...
    ChannelKind.SLACK -> R.drawable.ic_ch_slack
}
private fun channelName(kind: ChannelKind): String = when (kind) {
    // ...
    ChannelKind.SLACK -> "Slack"
}
```

### 6. Build & test

```bash
cd android-phone && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug
cd ../android-glasses && java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug
```

That's it — no wire-protocol or glasses-navigation changes are needed. The phone
aggregates your channel into the same inbox, and the reply/react/media/search
flows work automatically based on `canSend`/`canReact`/`fetchMedia`.

## Guidelines

- **Read-only source?** Set `canSend = false` and throw in `sendText`/`sendVoice`.
- **Ordering:** return `listMessages` **oldest-first** (reverse if the API is newest-first).
- **Media:** implement `fetchMedia` to return raw image/audio bytes; the phone
  downscales images and streams audio to the glasses / plays it.
- **Backend needed?** Mirror `telegram-bridge/` (small HTTP service with an
  `apikey` header) and make your `ChannelService` a thin HTTP client.
