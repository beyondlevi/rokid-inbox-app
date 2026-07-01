package com.rokid.inbox.phone.channels

import com.google.gson.JsonObject
import com.rokid.inbox.contracts.Chat
import com.rokid.inbox.contracts.ChannelKind
import com.rokid.inbox.contracts.ChatType
import com.rokid.inbox.contracts.Message
import okhttp3.Request

/**
 * GitHub Pull Requests as a read-only box: each PR is a chat, its body + issue
 * comments are the messages. Ported from even-inbox `src/channels/github.ts`.
 */
class GitHubService(
    override val boxId: String,
    private val token: String,
    query: String,
) : ChannelService {
    override val kind = ChannelKind.GITHUB
    override val canSend = false

    private val query: String = query.trim().ifBlank { DEFAULT_QUERY }
    private var me: String? = null

    private fun get(path: String): String {
        val request = Request.Builder()
            .url("$API$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        Http.client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RuntimeException("GitHub API ${res.code}: ${text.take(200).ifBlank { res.message }}")
            }
            return text
        }
    }

    override suspend fun ping() {
        get("/user")
    }

    private fun whoAmI(): String {
        me?.let { return it }
        val login = runCatching { Http.parse(get("/user")).obj().str("login") }.getOrDefault("")
        me = login
        return login
    }

    override suspend fun listChats(limit: Int): List<Chat> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val data = Http.parse(get("/search/issues?q=$q&sort=updated&order=desc&per_page=${minOf(limit, 50)}"))
        val items = data.obj().optArr("items") ?: return emptyList()
        return items.mapNotNull { it as? JsonObject }.take(limit).map { item ->
            val repo = repoFromUrl(item.str("repository_url"))
            val number = item.intOrNull("number") ?: 0
            Chat(
                channel = ChannelKind.GITHUB,
                boxId = boxId,
                id = "$repo#$number",
                name = "${item.str("title")} · $repo",
                type = ChatType.CHANNEL,
                unreadCount = 0,
                lastMessageDate = item.str("updated_at").ifBlank { null },
                lastMessagePreview = item.str("body").replace(Regex("\\s+"), " ").trim().take(120),
            )
        }
    }

    override suspend fun listMessages(chatId: String, limit: Int): List<Message> {
        val (repo, number) = parseChatId(chatId)
        val meLogin = whoAmI()
        val pr = Http.parse(get("/repos/$repo/pulls/$number")).obj()
        val msgs = ArrayList<Message>()
        val body = pr.str("body")
        if (body.isNotBlank()) {
            msgs += Message(
                id = "pr-$number",
                text = body,
                date = pr.str("created_at").ifBlank { null },
                isOutgoing = meLogin.isNotBlank() && pr.optObj("user").str("login") == meLogin,
                senderName = pr.optObj("user").str("login"),
            )
        }
        runCatching {
            val comments = Http.parse(get("/repos/$repo/issues/$number/comments?per_page=${minOf(limit, 100)}"))
            comments.arr()?.mapNotNull { it as? JsonObject }?.forEach { c ->
                msgs += Message(
                    id = c.longOrNull("id")?.toString() ?: c.str("id"),
                    text = c.str("body"),
                    date = c.str("created_at").ifBlank { null },
                    isOutgoing = meLogin.isNotBlank() && c.optObj("user").str("login") == meLogin,
                    senderName = c.optObj("user").str("login"),
                )
            }
        }
        // PR conversations read oldest->newest (description first).
        return msgs.sortedBy { epoch(it.date) }.take(limit)
    }

    override suspend fun sendText(chatId: String, text: String, replyToId: String, replyFromMe: Boolean) =
        throw UnsupportedOperationException("GitHub PRs é somente leitura")
    override suspend fun sendVoice(chatId: String, wav: ByteArray, durationSec: Int, replyToId: String, replyFromMe: Boolean) =
        throw UnsupportedOperationException("GitHub PRs é somente leitura")
    override suspend fun markAsRead(chatId: String, messages: List<Message>) {}

    private companion object {
        const val API = "https://api.github.com"
        const val DEFAULT_QUERY = "is:open is:pr involves:@me"

        fun repoFromUrl(url: String): String {
            val m = Regex("repos/([^/]+/[^/]+)$").find(url)
            return m?.groupValues?.get(1) ?: url
        }

        fun parseChatId(chatId: String): Pair<String, String> {
            val parts = chatId.split("#")
            return (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
        }

        fun epoch(iso: String?): Long {
            iso ?: return 0
            return runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
        }
    }
}
