package com.rokid.inbox.phone.channels

import com.rokid.inbox.contracts.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant

/**
 * Fetches chats from every connected channel in parallel and merges them into a
 * single inbox sorted by most recent activity. A failing channel never blocks
 * the others. Ported from even-inbox `src/inbox.ts`.
 */
object InboxAggregator {

    suspend fun fetchUnifiedInbox(services: List<ChannelService>, limit: Int = 20): List<Chat> = coroutineScope {
        val results = services.map { svc ->
            async(Dispatchers.IO) {
                runCatching { svc.listChats(limit) }.getOrElse {
                    android.util.Log.w("InboxAggregator", "channel ${svc.kind} failed", it)
                    emptyList()
                }
            }
        }.awaitAll()
        results.flatten().sortedByDescending { epoch(it.lastMessageDate) }
    }

    suspend fun searchChatsByName(
        services: List<ChannelService>,
        query: String,
        limit: Int = 200,
    ): List<Chat> = withContext(Dispatchers.IO) {
        val tokens = normalize(query).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return@withContext emptyList()
        val pool = fetchUnifiedInbox(services, limit)
        pool.filter { chat ->
            val name = normalize(chat.name)
            tokens.all { name.contains(it) }
        }
    }

    /** Strip accents, lowercase, punctuation -> space, collapse whitespace. */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun epoch(iso: String?): Long {
        iso ?: return 0
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
    }
}
