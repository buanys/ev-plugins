package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.SessionEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主循环的骨架：写进去的事件能不能被投影出正确历史、预算到线时能不能说清原因。
 */
class AgentLoopTest {

    private val model = ModelRef("fake", "fake-1")

    private fun loop(
        log: FakeLog,
        model: ev.buan.plugins.agent.api.ModelService,
        tools: List<FakeTool> = emptyList(),
        limits: AgentLoop.Limits = AgentLoop.Limits(),
    ) = AgentLoop(
        log = log,
        registry = FakeRegistry(tools),
        model = model,
        prompt = null,
        approval = ApprovalChain(log = log, gate = { null }),
        limits = limits,
    )

    @Test
    fun `一轮工具之后模型收尾，历史完整且结局是 FINISHED`() = runBlocking {
        val log = FakeLog()
        val tool = FakeTool(toolSpec("read")) { "文件内容" }
        val fakeModel = FakeModel(
            listOf(
                assistantWithCalls(AgentToolCall("c1", "read", "{}")),
                assistantText("看完了"),
            ),
        )

        val reply = loop(log, fakeModel, listOf(tool)).run("s", "读一下", model, null) {}

        assertEquals(AgentStop.Reason.FINISHED, reply.stop?.reason)
        assertEquals(2, reply.stop?.rounds)
        assertEquals(1, reply.stop?.toolCalls)
        assertEquals(listOf("读一下", "", "文件内容", "看完了"), log.contents())
        // 最后一条事件必须是 turn_end，而且是 FINISHED。
        val last = log.events.last()
        assertTrue(last is SessionEvent.TurnEnded)
        assertEquals(AgentStop.Reason.FINISHED, (last as SessionEvent.TurnEnded).stop.reason)
    }

    @Test
    fun `模型不再调工具就结束，不会多跑一轮`() = runBlocking {
        val log = FakeLog()
        val fakeModel = FakeModel(listOf(assistantText("直接回答")))

        val reply = loop(log, fakeModel).run("s", "你好", model, null) {}

        assertEquals(1, fakeModel.calls)
        assertEquals(AgentStop.Reason.FINISHED, reply.stop?.reason)
    }

    @Test
    fun `回合预算到线就停，原因说清`() = runBlocking {
        val log = FakeLog()
        var n = 0
        val tool = FakeTool(toolSpec("step")) { "第 ${++n} 次" }
        // 每次都要求调工具：永远不自己收尾。
        val fakeModel = FakeModel(listOf(assistantWithCalls(AgentToolCall("c", "step", "{}"))))

        val reply = loop(log, fakeModel, listOf(tool), AgentLoop.Limits(maxRounds = 3))
            .run("s", "跑", model, null) {}

        assertEquals(AgentStop.Reason.ROUNDS, reply.stop?.reason)
        assertEquals(3, reply.stop?.rounds)
        assertEquals(3, fakeModel.calls)
    }

    @Test
    fun `工具总量预算到线就停`() = runBlocking {
        val log = FakeLog()
        var n = 0
        val tool = FakeTool(toolSpec("step")) { "第 ${++n} 次" }
        val fakeModel = FakeModel(
            listOf(
                assistantWithCalls(
                    AgentToolCall("a", "step", """{"i":1}"""),
                    AgentToolCall("b", "step", """{"i":2}"""),
                ),
            ),
        )

        val reply = loop(log, fakeModel, listOf(tool), AgentLoop.Limits(maxToolCalls = 3))
            .run("s", "跑", model, null) {}

        assertEquals(AgentStop.Reason.TOOL_BUDGET, reply.stop?.reason)
    }

    @Test
    fun `同样的调用得到同样的结果两次就停`() = runBlocking {
        val log = FakeLog()
        val tool = FakeTool(toolSpec("loop")) { "永远一样" }
        val fakeModel = FakeModel(listOf(assistantWithCalls(AgentToolCall("c", "loop", "{}"))))

        val reply = loop(log, fakeModel, listOf(tool)).run("s", "跑", model, null) {}

        assertEquals(AgentStop.Reason.REPEATED_CALLS, reply.stop?.reason)
    }

    @Test
    fun `模型抛错不往宿主抛，变成 ERROR 结局`() = runBlocking {
        val log = FakeLog()
        val broken = object : ev.buan.plugins.agent.api.ModelService {
            override val models = listOf(model)
            override suspend fun complete(
                model: ModelRef,
                messages: List<ev.buan.plugins.agent.api.AgentMessage>,
                tools: List<ev.buan.plugins.agent.api.ToolSpec>,
            ): ev.buan.plugins.agent.api.AgentReply = error("网络断了")
        }

        val reply = loop(log, broken).run("s", "你好", model, null) {}

        assertEquals(AgentStop.Reason.ERROR, reply.stop?.reason)
        assertTrue(reply.message.content.contains("网络断了"))
        assertTrue(log.events.last() is SessionEvent.TurnEnded)
    }

    @Test
    fun `危险工具没人审批时被拒，但这一轮继续`() = runBlocking {
        val log = FakeLog()
        val tool = FakeTool(toolSpec("rm", dangerous = true)) { "删了" }
        val fakeModel = FakeModel(
            listOf(
                assistantWithCalls(AgentToolCall("c1", "rm", "{}")),
                assistantText("没删成"),
            ),
        )

        val reply = loop(log, fakeModel, listOf(tool)).run("s", "删掉", model, null) {}

        assertEquals(0, tool.invocations)
        assertEquals(AgentStop.Reason.FINISHED, reply.stop?.reason)
        // 拒绝理由要作为 tool 结果进历史，模型下一轮才看得见。
        assertTrue(log.contents().any { it.contains("拿不到审批") })
    }
}
