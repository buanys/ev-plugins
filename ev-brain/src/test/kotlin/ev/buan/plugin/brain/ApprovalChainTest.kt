package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalOutcome
import ev.buan.plugins.agent.api.SessionEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批链的全部失败模式。这一层的错误等于"危险操作绕过了人"，所以每条路径都要有用例。
 */
class ApprovalChainTest {

    private val call = AgentToolCall("c1", "run_shell", """{"cmd":"rm -rf /"}""")

    private fun chain(log: FakeLog?, gate: FakeGate?) = ApprovalChain(
        log = log,
        gate = { gate },
        newId = { "fixed-id" },
        onAuditFailure = {},
    )

    private fun auditOf(log: FakeLog): List<SessionEvent> =
        log.events.filter { it is SessionEvent.ApprovalAsked || it is SessionEvent.ApprovalDecided }

    @Test
    fun `没有审批门时按拒绝处理`() = runBlocking {
        val log = FakeLog()
        val reason = chain(log, gate = null).authorize("s", call)

        assertNotNull("拿不到审批就必须给出拒绝理由", reason)
        assertTrue(reason!!.contains("拿不到审批"))
        assertEquals(
            ApprovalOutcome.UNAVAILABLE,
            (auditOf(log).last() as SessionEvent.ApprovalDecided).outcome,
        )
    }

    @Test
    fun `允许一次就放行并留下成对审计`() = runBlocking {
        val log = FakeLog()
        val reason = chain(log, FakeGate { _, _ -> ApprovalDecision.ALLOW_ONCE }).authorize("s", call)

        assertNull(reason)
        val audit = auditOf(log)
        assertEquals(2, audit.size)
        assertEquals("fixed-id", (audit[0] as SessionEvent.ApprovalAsked).id)
        assertEquals("fixed-id", (audit[1] as SessionEvent.ApprovalDecided).id)
        assertEquals(
            ApprovalOutcome.ALLOWED_ONCE,
            (audit[1] as SessionEvent.ApprovalDecided).outcome,
        )
    }

    @Test
    fun `会话内允许也是放行，但记录成 ALLOWED_SESSION`() = runBlocking {
        val log = FakeLog()
        val reason = chain(log, FakeGate { _, _ -> ApprovalDecision.ALLOW_SESSION }).authorize("s", call)

        assertNull(reason)
        assertEquals(
            ApprovalOutcome.ALLOWED_SESSION,
            (auditOf(log).last() as SessionEvent.ApprovalDecided).outcome,
        )
    }

    @Test
    fun `人拒绝时给出理由`() = runBlocking {
        val reason = chain(FakeLog(), FakeGate { _, _ -> ApprovalDecision.DENY }).authorize("s", call)

        assertNotNull(reason)
        assertTrue(reason!!.contains("拒绝"))
    }

    @Test
    fun `审批门抛错时按不可用处理，而不是放行`() = runBlocking {
        val log = FakeLog()
        val gate = FakeGate { _, _ -> error("门坏了") }
        val reason = chain(log, gate).authorize("s", call)

        assertNotNull("门坏了必须 fail-closed", reason)
        assertEquals(
            ApprovalOutcome.UNAVAILABLE,
            (auditOf(log).last() as SessionEvent.ApprovalDecided).outcome,
        )
    }

    @Test
    fun `审计写失败不影响决策`() = runBlocking {
        // 日志坏掉不该让"允许"变成"拒绝"，也不该让"拒绝"变成"允许"。
        val broken = object : ev.buan.plugins.agent.api.SessionLog {
            override suspend fun append(sessionId: String, event: SessionEvent) = error("存储坏了")
            override suspend fun messages(sessionId: String) = emptyList<ev.buan.plugins.agent.api.AgentMessage>()
            override suspend fun events(sessionId: String) = emptyList<SessionEvent>()
            override suspend fun clear(sessionId: String) = Unit
        }
        val chain = ApprovalChain(log = broken, gate = { FakeGate { _, _ -> ApprovalDecision.ALLOW_ONCE } }, newId = { "id" }, onAuditFailure = {})

        assertNull(chain.authorize("s", call))
    }

    @Test
    fun `问之前就记一笔，问之后才记答案`() = runBlocking {
        val log = FakeLog()
        chain(log, FakeGate { _, _ -> ApprovalDecision.DENY }).authorize("s", call)

        // 顺序：asked 在前，decided 在后。反过来就说明吞了崩溃点。
        assertTrue(log.events[0] is SessionEvent.ApprovalAsked)
        assertTrue(log.events[1] is SessionEvent.ApprovalDecided)
    }
}
