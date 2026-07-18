package org.impfai.hermes.core.audit

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本機審計日誌：追加/讀取/裁剪/清除 + caseId 確定性。 */
class AuditLogTest {

    private fun tempDir(): File =
        Files.createTempDirectory("audit-test").toFile().apply { deleteOnExit() }

    private fun entry(i: Int) = AuditLog.Entry(
        caseId = "case-$i", ts = "2026-07-17T00:00:0${i % 10}Z",
        kind = "agent", input = "问题 $i", requestedRole = "student",
        backend = "local", evidence = listOf("SHL_SONGBEN_0035"),
        verdict = "引用已核验 1 条",
    )

    @Test
    fun `record then recent returns newest first`() = runBlocking {
        val log = AuditLog(tempDir())
        log.record(entry(1))
        log.record(entry(2))
        log.record(entry(3))
        assertEquals(3, log.count())
        val recent = log.recent(2)
        assertEquals(listOf("case-3", "case-2"), recent.map { it.caseId })
        assertEquals("问题 3", recent[0].input)
        assertEquals(listOf("SHL_SONGBEN_0035"), recent[0].evidence)
    }

    @Test
    fun `entries beyond maxEntries are trimmed oldest-first`() = runBlocking {
        val log = AuditLog(tempDir(), maxEntries = 5)
        repeat(8) { log.record(entry(it)) }
        assertEquals(5, log.count())
        val ids = log.recent(10).map { it.caseId }
        assertEquals(listOf("case-7", "case-6", "case-5", "case-4", "case-3"), ids)
    }

    @Test
    fun `clear removes everything and is idempotent`() = runBlocking {
        val log = AuditLog(tempDir())
        log.record(entry(1))
        log.clear()
        assertEquals(0, log.count())
        assertTrue(log.recent().isEmpty())
        log.clear()   // 再清一次不拋
        assertEquals(0, log.count())
    }

    @Test
    fun `corrupt lines are skipped, not fatal`() = runBlocking {
        val dir = tempDir()
        val log = AuditLog(dir)
        log.record(entry(1))
        File(dir, "audit.jsonl").appendText("{not-json}\n")
        log.record(entry(2))
        val recent = log.recent(10)
        assertEquals(listOf("case-2", "case-1"), recent.map { it.caseId })
    }

    @Test
    fun `case id derives from utc instant deterministically`() {
        val id = AuditLog.newCaseId(Instant.parse("2026-07-17T09:30:12.483Z"))
        assertEquals("20260717-093012-483", id)
        assertEquals("2026-07-17T09:30:12.483Z",
            AuditLog.timestamp(Instant.parse("2026-07-17T09:30:12.483Z")))
    }
}
