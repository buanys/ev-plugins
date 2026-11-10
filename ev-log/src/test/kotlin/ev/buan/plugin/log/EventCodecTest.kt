package ev.buan.plugin.log

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalOutcome
import ev.buan.plugins.agent.api.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编解码的守卫：写下去的事实，读回来必须一模一样。
 *
 * 特别注意两处有意的损失：
 * - 图片不落盘（MB 级瞬态数据）
 * - 不认识的类型返回 null 而不是抛（一行坏掉不该毁掉整个会话）
 */
class EventCodecTest {

    private fun roundTrip(event: SessionEvent): SessionEvent? =
        EventCodec.decode(EventCodec.encode(event))

    @Test
    fun `普通消息往返一致`() {
        val event = SessionEvent.Message(AgentMessage(AgentRole.USER, "你好\n第二行"), at = 1_000L)
        assertEquals(event, roundTrip(event))
    }

    @Test
    fun `带工具调用的助手消息往返一致`() {
        val event = SessionEvent.Message(
            AgentMessage(
                role = AgentRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    AgentToolCall("c1", "read", """{"path":"/a"}"""),
                    AgentToolCall("c2", "write", "{}"),
                ),
            ),
            at = 2_000L,
        )
        assertEquals(event, roundTrip(event))
    }

    @Test
    fun `工具结果保留关联 id 与工具名`() {
        val event = SessionEvent.Message(
            AgentMessage(AgentRole.TOOL, "结果", name = "read", toolCallId = "c1"),
            at = 3_000L,
        )
        assertEquals(event, roundTrip(event))
    }

    @Test
    fun `图片不落盘但文字保留`() {
        val event = SessionEvent.Message(
            AgentMessage(
                role = AgentRole.TOOL,
                content = "截图如下",
                images = listOf(AgentImage("image/png", "AAAA")),
            ),
            at = 4_000L,
        )
        val decoded = roundTrip(event) as SessionEvent.Message
        assertEquals("截图如下", decoded.message.content)
        assertTrue("图片不该被写进日志", decoded.message.images.isEmpty())
    }

    @Test
    fun `轮次结束保留原因与计数`() {
        val event = SessionEvent.TurnEnded(
            AgentStop(AgentStop.Reason.ROUNDS, rounds = 12, toolCalls = 5, detail = "预算用尽"),
            at = 5_000L,
        )
        assertEquals(event, roundTrip(event))
    }

    @Test
    fun `审批的两个阶段都能往返`() {
        val asked = SessionEvent.ApprovalAsked("a1", "run_shell", "c9", at = 6_000L)
        val decided = SessionEvent.ApprovalDecided("a1", ApprovalOutcome.UNAVAILABLE, at = 7_000L)
        assertEquals(asked, roundTrip(asked))
        assertEquals(decided, roundTrip(decided))
    }

    @Test
    fun `放弃记录保留原因`() {
        val event = SessionEvent.ToolAbandoned("c1", "shell", AbandonReason.NOT_STARTED, at = 8_000L)
        assertEquals(event, roundTrip(event))
    }

    @Test
    fun `不认识的类型返回 null 而不是抛`() {
        assertNull(EventCodec.decode("""{"type":"未来版本的新事件","at":1}"""))
    }

    @Test
    fun `坏 JSON 返回 null`() {
        assertNull(EventCodec.decode("{不是 json"))
        assertNull(EventCodec.decode(""))
    }

    @Test
    fun `缺字段不会炸掉整条会话`() {
        assertNull(EventCodec.decode("""{"type":"turn_end","at":1}"""))
    }
}
