package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器的三条纪律，各有一组用例：
 * 顺序不能乱、单个失败不能炸批、危险工具没批准就不能跑。
 */
class ToolPipelineTest {

    private fun call(id: String, name: String, args: String = "{}") = AgentToolCall(id, name, args)

    private suspend fun run(
        calls: List<AgentToolCall>,
        tools: List<FakeTool>,
        authorize: suspend (AgentToolCall, ev.buan.plugins.agent.api.ToolSpec) -> String? = { _, _ -> null },
    ): List<ToolPipeline.Outcome> = ToolPipeline().execute(
        calls = calls,
        registry = FakeRegistry(tools),
        authorize = authorize,
        onSignal = {},
    )

    @Test
    fun `结果顺序与调用顺序一致`() = runBlocking {
        val slow = FakeTool(toolSpec("slow", parallelSafe = true)) { kotlinx.coroutines.delay(60); "slow" }
        val fast = FakeTool(toolSpec("fast", parallelSafe = true)) { "fast" }

        val outcomes = run(listOf(call("1", "slow"), call("2", "fast")), listOf(slow, fast))

        assertEquals(listOf("slow", "fast"), outcomes.map { it.text })
    }

    @Test
    fun `并行安全的一组会并发执行`() = runBlocking {
        val tool = FakeTool(toolSpec("wait", parallelSafe = true)) { kotlinx.coroutines.delay(80); "ok" }
        val started = System.currentTimeMillis()
        run(List(4) { call("$it", "wait") }, listOf(tool))
        val elapsed = System.currentTimeMillis() - started

        // 串行要 320ms 以上；并发（上限 10）应远低于它。
        assertTrue("4 个 80ms 的调用应当并发，实际 ${elapsed}ms", elapsed < 260)
    }

    @Test
    fun `独占调用会串行`() = runBlocking {
        val tool = FakeTool(toolSpec("exclusive")) { kotlinx.coroutines.delay(40); "ok" }
        val started = System.currentTimeMillis()
        run(List(3) { call("$it", "exclusive") }, listOf(tool))
        val elapsed = System.currentTimeMillis() - started

        assertTrue("独占调用应当串行，实际 ${elapsed}ms", elapsed >= 110)
    }

    @Test
    fun `工具抛错变成结果文本，兄弟调用继续`() = runBlocking {
        val broken = FakeTool(toolSpec("broken")) { error("炸了") }
        val fine = FakeTool(toolSpec("fine")) { "fine" }

        val outcomes = run(listOf(call("1", "broken"), call("2", "fine")), listOf(broken, fine))

        assertTrue(outcomes[0].isError)
        assertTrue(outcomes[0].text.startsWith("Error:"))
        assertFalse(outcomes[1].isError)
        assertEquals("fine", outcomes[1].text)
    }

    @Test
    fun `未知工具不会抛，而是一条错误结果`() = runBlocking {
        val outcomes = run(listOf(call("1", "不存在")), emptyList())
        assertTrue(outcomes.single().isError)
    }

    @Test
    fun `危险工具没有批准就不执行`() = runBlocking {
        val tool = FakeTool(toolSpec("rm", dangerous = true)) { "删了" }

        val outcomes = run(listOf(call("1", "rm")), listOf(tool)) { _, _ -> "用户拒绝" }

        assertEquals(0, tool.invocations)
        assertTrue(outcomes.single().isError)
        assertTrue(outcomes.single().text.contains("用户拒绝"))
    }

    @Test
    fun `危险工具批准后才执行`() = runBlocking {
        val tool = FakeTool(toolSpec("rm", dangerous = true)) { "删了" }
        val outcomes = run(listOf(call("1", "rm")), listOf(tool)) { _, _ -> null }
        assertEquals(1, tool.invocations)
        assertEquals("删了", outcomes.single().text)
    }

    @Test
    fun `耗时由执行器自己测，不是上层编的`() = runBlocking {
        val tool = FakeTool(toolSpec("slow")) { kotlinx.coroutines.delay(50); "ok" }
        val outcomes = run(listOf(call("1", "slow")), listOf(tool))
        assertTrue("应当测到耗时，实际 ${outcomes.single().elapsedMs}ms", outcomes.single().elapsedMs >= 40)
    }

    @Test
    fun `取消时把没结果的调用如实记成放弃`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val tool = FakeTool(toolSpec("slow")) {
            entered.complete(Unit)
            delay(5_000)
            "never"
        }
        val signals = mutableListOf<ToolPipeline.Signal>()

        val job = launch {
            ToolPipeline().execute(
                calls = listOf(call("1", "slow"), call("2", "slow")),
                registry = FakeRegistry(listOf(tool)),
                authorize = { _, _ -> null },
                onSignal = { signals += it },
            )
        }
        entered.await()
        job.cancelAndJoin()

        val abandoned = signals.filterIsInstance<ToolPipeline.Signal.Abandoned>()
        val all = abandoned.flatMap { it.calls }
        assertEquals(
            "跑过的记 OUTCOME_UNKNOWN，没跑到的记 NOT_STARTED",
            setOf("1", "2"),
            all.map { it.id }.toSet(),
        )
        assertTrue(abandoned.any { it.reason == AbandonReason.OUTCOME_UNKNOWN })
        assertTrue(abandoned.any { it.reason == AbandonReason.NOT_STARTED })
    }

    @Test
    fun `普通工具不走审批`() = runBlocking {
        var asked = 0
        val tool = FakeTool(toolSpec("read")) { "内容" }
        run(listOf(call("1", "read")), listOf(tool)) { _, _ -> asked++; null }
        assertEquals(0, asked)
    }
}
