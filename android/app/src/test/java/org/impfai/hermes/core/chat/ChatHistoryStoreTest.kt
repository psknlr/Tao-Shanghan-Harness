package org.impfai.hermes.core.chat

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 會話持久化：存取/排序/裁剪/壞文件容錯。 */
class ChatHistoryStoreTest {

    private fun tempDir(): File =
        Files.createTempDirectory("chat-test").toFile().apply { deleteOnExit() }

    private fun session(id: String, updated: String, title: String = "问题 $id") =
        ChatHistoryStore.Session(
            id = id, createdTs = updated, updatedTs = updated, title = title,
            source = "server",
            messages = listOf(
                ChatHistoryStore.Message(role = "user", text = title),
                ChatHistoryStore.Message(role = "bot", answer = "回答",
                    backend = "local", evidence = listOf("SHL_SONGBEN_0001"),
                    citation = "verified"),
            ),
        )

    @Test
    fun `save then list returns newest-updated first`() = runBlocking {
        val store = ChatHistoryStore(tempDir())
        store.save(session("a", "2026-07-18T01:00:00Z"))
        store.save(session("b", "2026-07-18T03:00:00Z"))
        store.save(session("c", "2026-07-18T02:00:00Z"))
        assertEquals(listOf("b", "c", "a"), store.list().map { it.id })
        assertEquals(3, store.count())
    }

    @Test
    fun `load restores full messages, delete removes`() = runBlocking {
        val store = ChatHistoryStore(tempDir())
        store.save(session("x", "2026-07-18T01:00:00Z"))
        val loaded = store.load("x")!!
        assertEquals(2, loaded.messages.size)
        assertEquals("verified", loaded.messages[1].citation)
        assertEquals(listOf("SHL_SONGBEN_0001"), loaded.messages[1].evidence)
        store.delete("x")
        assertNull(store.load("x"))
        assertEquals(0, store.count())
    }

    @Test
    fun `overwrite same id updates in place, not duplicates`() = runBlocking {
        val store = ChatHistoryStore(tempDir())
        store.save(session("s", "2026-07-18T01:00:00Z"))
        store.save(session("s", "2026-07-18T02:00:00Z", title = "改后"))
        assertEquals(1, store.count())
        assertEquals("改后", store.load("s")!!.title)
    }

    @Test
    fun `sessions beyond maxSessions trim oldest`() = runBlocking {
        val store = ChatHistoryStore(tempDir(), maxSessions = 3)
        repeat(5) { i ->
            store.save(session("s$i", "2026-07-18T0$i:00:00Z"))
        }
        assertEquals(3, store.count())
        assertEquals(listOf("s4", "s3", "s2"), store.list().map { it.id })
    }

    @Test
    fun `corrupt file is skipped, not fatal`() = runBlocking {
        val dir = tempDir()
        val store = ChatHistoryStore(dir)
        store.save(session("good", "2026-07-18T01:00:00Z"))
        File(dir, "bad.json").writeText("{not-json")
        assertEquals(listOf("good"), store.list().map { it.id })
    }

    @Test
    fun `session id derives from utc instant and is filename-safe`() {
        val id = ChatHistoryStore.newSessionId(
            Instant.parse("2026-07-18T09:30:12.483Z"))
        assertEquals("chat-20260718-093012-483", id)
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("2026-07-18 09:30",
            ChatHistoryStore.shortTime("2026-07-18T09:30:12.483Z"))
    }
}
