package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentImage
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 一批工具调用的执行器。
 *
 * 照 DeepSeek Harness 的三条纪律，它们各自解决一个真实故障：
 *
 * 1. **分组而不是简单并发**。[ToolSpec.parallelSafe] 为 true 的连续调用进有界滚动池，
 *    其余都是**屏障**——串行执行，且会切断前后两段并行。默认 false 是 fail-closed：
 *    没声明的工具不会被塞进池子里去踩别人的状态。
 * 2. **执行可以重叠，结果必须按模型顺序落地**。槽位按下标预先分配，
 *    谁先跑完不影响 [Outcome] 列表的顺序；否则模型看到的 tool 结果会和它的调用对不上。
 * 3. **单个工具体抛错不炸整批**。异常转成 `Error: …` 结果，兄弟调用继续。
 *    整批失败只属于调度器自身故障，这里没有那种路径。
 *
 * 取消是特例：[CancellationException] 会先把**还没结果的调用**如实记成放弃
 * （[AbandonReason.NOT_STARTED] 或 [AbandonReason.OUTCOME_UNKNOWN]），再把取消原样抛出。
 * 这一步必须做，否则会话日志里会留下"请求了工具、永远没有结果"的裂口。
 */
internal class ToolPipeline(
    private val maxParallel: Int = DEFAULT_MAX_PARALLEL,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一次调用的结局。[isError] 为 true 时 [text] 是一条 `Error: …`，模型据此换路。 */
    data class Outcome(
        val call: AgentToolCall,
        val text: String,
        val isError: Boolean,
        val images: List<AgentImage> = emptyList(),
        /** 这次调用花了多久。由执行器自己测——上层不该编一个数字出来。 */
        val elapsedMs: Long = 0,
    )

    /** 执行器对外播报的信号，用来驱动 UI 的进度。 */
    sealed interface Signal {
        data class Started(val call: AgentToolCall) : Signal
        data class Finished(val outcome: Outcome) : Signal
        /** 取消时对没能落地的调用的补记通知。语义与 [Finished] 同级，不是另一套通道。 */
        data class Abandoned(val calls: List<AgentToolCall>, val reason: AbandonReason) : Signal
    }

    /**
     * 执行 [calls]，返回与它**一一对应且同序**的结果。
     *
     * @param authorize 危险工具的审批回调，返回拒绝理由；null 表示放行。
     * @param onSignal 一切观察都走这一条：开始、结束、取消时的补记。
     */
    suspend fun execute(
        calls: List<AgentToolCall>,
        registry: ToolRegistry?,
        authorize: suspend (AgentToolCall, ToolSpec) -> String?,
        onSignal: suspend (Signal) -> Unit,
    ): List<Outcome> {
        val slots = arrayOfNulls<Outcome>(calls.size)
        val started = BooleanArray(calls.size)

        try {
            var index = 0
            while (index < calls.size) {
                if (specOf(registry, calls[index].name)?.parallelSafe == true) {
                    var end = index
                    while (end < calls.size && specOf(registry, calls[end].name)?.parallelSafe == true) end++

                    // 整段标记为"已开始"：池子里谁先跑不确定，取消时按最保守的原因记账。
                    for (slot in index until end) started[slot] = true
                    coroutineScope {
                        val permits = Semaphore(maxParallel.coerceAtLeast(1))
                        (index until end).map { slot ->
                            async {
                                permits.withPermit {
                                    slots[slot] = runOne(calls[slot], registry, authorize, onSignal)
                                }
                            }
                        }.awaitAll()
                    }
                    index = end
                } else {
                    started[index] = true
                    slots[index] = runOne(calls[index], registry, authorize, onSignal)
                    index++
                }
            }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                // 没拿到结果的一律要记账；是否开跑只影响记哪种原因。
                val pending = calls.indices.filter { slots[it] == null }
                if (pending.isNotEmpty()) {
                    val neverRan = pending.filterNot { started[it] }.map { calls[it] }
                    val unknown = pending.filter { started[it] }.map { calls[it] }
                    if (neverRan.isNotEmpty()) onSignal(Signal.Abandoned(neverRan, AbandonReason.NOT_STARTED))
                    if (unknown.isNotEmpty()) onSignal(Signal.Abandoned(unknown, AbandonReason.OUTCOME_UNKNOWN))
                }
            }
            throw cancellation
        }

        return calls.indices.map { slot ->
            slots[slot] ?: Outcome(calls[slot], "Error: 调用没有产生结果（$UNKNOWN）", true)
        }
    }

    private suspend fun runOne(
        call: AgentToolCall,
        registry: ToolRegistry?,
        authorize: suspend (AgentToolCall, ToolSpec) -> String?,
        onSignal: suspend (Signal) -> Unit,
    ): Outcome {
        val tool = registry?.lookup(call.name)
            ?: return Outcome(call, "Error: 没有叫 ${call.name} 的工具", true)

        onSignal(Signal.Started(call))
        val startedAt = clock()

        // 审批在**执行之前**：危险工具必须拿到明确放行，拿不到就不跑。
        if (tool.spec.dangerous) {
            val refusal = authorize(call, tool.spec)
            if (refusal != null) {
                val denied = Outcome(call, "Error: $refusal", true, elapsedMs = clock() - startedAt)
                onSignal(Signal.Finished(denied))
                return denied
            }
        }

        val outcome = try {
            val result = tool.invokeResult(call.argumentsJson)
            Outcome(call, result.text, false, result.images, clock() - startedAt)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 工具自己炸了：变成结果文本，兄弟调用继续，这一轮不因此失败。
            Outcome(call, "Error: ${error.message ?: error::class.simpleName}", true, elapsedMs = clock() - startedAt)
        }

        onSignal(Signal.Finished(outcome))
        return outcome
    }

    private fun specOf(registry: ToolRegistry?, name: String): ToolSpec? = registry?.lookup(name)?.spec

    companion object {
        const val DEFAULT_MAX_PARALLEL = 10
        private const val UNKNOWN = "执行器没有回报"
    }
}
