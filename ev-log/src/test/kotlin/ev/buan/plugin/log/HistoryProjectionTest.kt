package ev.buan.plugin.log

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalOutcome
import ev.buan.plugins.agent.api.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派生规则的守卫：同样的日志永远得到同样的消息序列。
 *
 * 两组要点：
 * - 只有 `inModelContext` 的事件进历史（轮次边界、审批审计不进）
 * - 被放弃的调用要变成一条失败结果，否则模型下一轮会重复调用
 */
class HistoryProjectionTest {

    private fun user(text: String) = SessionEvent.Message(AgentMessage(AgentRole.USER, text))
    private fun assistant(text: String, calls: List<AgentToolCall> = emptyList()) =
        SessionEvent.Message(AgentMessage(AgentRole.ASSISTANT, text, toolCalls = calls))
    private fun toolResult(callId: String, text: String) =
        SessionEvent.Message(AgentMessage(AgentRole.TOOL, text, toolCallId = callId))

    @Test
    fun `只有模型可见事件进历史`() {
        val events = listOf(
            user("你好"),
            assistant("我先看看"),
            SessionEvent.TurnEnded(AgentStop(AgentStop.Reason.FINISHED, rounds = 1, toolCalls = 0)),
            SessionEvent.ApprovalAsked("a1", "run_shell"),
            SessionEvent.ApprovalDecided("a1", ApprovalOutcome.ALLOWED_ONCE),
        )

        assertEquals(listOf("你好", "我先看看"), HistoryProjection.messages(events).map { it.content })
    }

    @Test
    fun `顺序保持追加顺序`() {
        val events = listOf(user("一"), assistant("二"), user("三"))
        assertEquals(listOf("一", "二", "三"), HistoryProjection.messages(events).map { it.content })
    }

    @Test
    fun `被放弃的调用变成一条失败结果`() {
        val call = AgentToolCall("c1", "run_shell", "{}")
        val events = listOf(
            user("跑一下"),
            assistant("", listOf(call)),
            SessionEvent.ToolAbandoned("c1", "run_shell", AbandonReason.OUTCOME_UNKNOWN),
        )

        val messages = HistoryProjection.messages(events)
        val result = messages.last()
        assertEquals(AgentRole.TOOL, result.role)
        assertEquals("c1", result.toolCallId)
        assertTrue("要说清结果未知", result.content.contains("未知"))
        assertTrue("要提醒先核实外部状态", result.content.contains("核实"))
    }

    @Test
    fun `还没开始跑的放弃记录鼓励重试`() {
        val events = listOf(
            assistant("", listOf(AgentToolCall("c1", "read", "{}"))),
            SessionEvent.ToolAbandoned("c1", "read", AbandonReason.NOT_STARTED),
        )
        assertTrue(HistoryProjection.messages(events).last().content.contains("可以安全重试"))
    }

    @Test
    fun `已有真实结果时不再合成假结果`() {
        val events = listOf(
            assistant("", listOf(AgentToolCall("c1", "read", "{}"))),
            toolResult("c1", "文件内容"),
            SessionEvent.ToolAbandoned("c1", "read", AbandonReason.OUTCOME_UNKNOWN),
        )
        val messages = HistoryProjection.messages(events)
        assertEquals(2, messages.size)
        assertEquals("文件内容", messages.last().content)
    }

    @Test
    fun `洞的判定只看没结果的调用`() {
        val answered = AgentToolCall("c1", "read", "{}")
        val missing = AgentToolCall("c2", "write", "{}")
        val abandoned = AgentToolCall("c3", "shell", "{}")
        val events = listOf(
            assistant("", listOf(answered, missing, abandoned)),
            toolResult("c1", "ok"),
            SessionEvent.ToolAbandoned("c3", "shell", AbandonReason.NOT_STARTED),
        )

        assertEquals(listOf("c2"), HistoryProjection.unansweredCalls(events).map { it.id })
    }

    @Test
    fun `重复的 toolCallId 只算一次`() {
        val call = AgentToolCall("c1", "read", "{}")
        val events = listOf(assistant("", listOf(call)), assistant("", listOf(call)))
        assertEquals(1, HistoryProjection.unansweredCalls(events).size)
    }

    @Test
    fun `空日志派生出空历史`() {
        assertTrue(HistoryProjection.messages(emptyList()).isEmpty())
        assertTrue(HistoryProjection.unansweredCalls(emptyList()).isEmpty())
    }
}
