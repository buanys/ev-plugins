package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentEvent
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.AgentUsage
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.PromptContext
import ev.buan.plugins.agent.api.PromptLayer
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolRegistry
import kotlinx.coroutines.CancellationException

/**
 * 一轮对话的主循环。
 *
 * 结构照 DeepSeek Harness 的 `ReactLoopAgent`，但按 EV 的契约收窄：
 *
 * ```
 * 写 user 事件 → 循环 { 组装请求 → 模型 → 写 assistant 事件
 *                      → 没有工具调用就结束 → 否则执行这批并写 tool 事件 }
 *              → 写 turn_end → 返回 AgentReply
 * ```
 *
 * ## 四条边界
 *
 * - **预算在内核**。回合数、工具调用总数、墙钟上限都在这里判，不指望插件。
 *   `AgentStop.Reason` 里那三个原因就是这三条线，超了要说清楚。
 * - **一切失败都变成可读的结局**，不往宿主抛。模型调用炸了 → `stop=ERROR` 且带上原因；
 *   工具炸了 → 由 [ToolPipeline] 变成 `Error: …` 结果，这一轮继续。
 * - **历史只从日志来**。每一轮都重新 `log.messages()`，不在内存里另存一份对话——
 *   那样一旦崩溃恢复，内存和磁盘就会各说各话。
 * - **取消要留痕**。取消发生时先补记被放弃的工具调用，再把取消抛上去。
 *
 * [AgentStop.Reason.NO_PROGRESS] 目前不会产生：一轮里没有工具调用就直接结束了，
 * "连续两轮说同一句话"这个条件在同一轮内不可能成立。留待将来跨轮检测。
 */
internal class AgentLoop(
    private val log: SessionLog,
    private val registry: ToolRegistry?,
    private val model: ModelService,
    private val prompt: SystemPromptService?,
    private val approval: ApprovalChain,
    private val pipeline: ToolPipeline = ToolPipeline(),
    private val limits: Limits = Limits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Limits(
        val maxRounds: Int = 12,
        val maxToolCalls: Int = 40,
        val deadlineMs: Long = 300_000,
    )

    suspend fun run(
        sessionId: String,
        text: String,
        modelRef: ModelRef,
        persona: String?,
        onProgress: suspend (AgentProgress) -> Unit,
    ): AgentReply {
        log.append(sessionId, SessionEvent.Message(AgentMessage(AgentRole.USER, text)))

        val startedAt = clock()
        val events = mutableListOf<AgentEvent>()
        var rounds = 0
        var toolCalls = 0
        var lastAssistant: AgentMessage? = null
        var stop: AgentStop? = null
        var usage = AgentUsage()
        // 同一工具、同样参数、同样的结果出现两次 = 模型在原地打转。
        val seen = mutableMapOf<String, String>()

        while (stop == null) {
            if (rounds >= limits.maxRounds) {
                stop = AgentStop(AgentStop.Reason.ROUNDS, rounds, toolCalls)
                break
            }
            if (clock() - startedAt >= limits.deadlineMs) {
                stop = AgentStop(AgentStop.Reason.DEADLINE, rounds, toolCalls)
                break
            }

            rounds++
            onProgress(AgentProgress.RoundStarted(rounds))

            val request = buildRequest(sessionId, persona)
            val reply = try {
                model.completeStreaming(modelRef, request, registry?.specs().orEmpty()) { delta ->
                    onProgress(AgentProgress.TextDelta(rounds, delta))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val detail = error.message ?: error::class.simpleName ?: "未知错误"
                val message = AgentMessage(AgentRole.ASSISTANT, "模型调用失败：$detail")
                log.append(sessionId, SessionEvent.Message(message))
                events += AgentEvent(AgentEvent.Kind.ERROR, detail)
                lastAssistant = message
                stop = AgentStop(AgentStop.Reason.ERROR, rounds, toolCalls, detail)
                break
            }

            usage = usage.plus(reply.usage)
            lastAssistant = reply.message
            if (reply.message.content.isNotBlank()) {
                events += AgentEvent(AgentEvent.Kind.TEXT, reply.message.content)
            }
            log.append(sessionId, SessionEvent.Message(reply.message))
            onProgress(AgentProgress.RoundFinished(rounds))

            val calls = reply.message.toolCalls
            if (calls.isEmpty()) {
                stop = AgentStop(AgentStop.Reason.FINISHED, rounds, toolCalls)
                break
            }
            if (toolCalls + calls.size > limits.maxToolCalls) {
                stop = AgentStop(AgentStop.Reason.TOOL_BUDGET, rounds, toolCalls)
                break
            }
            toolCalls += calls.size

            val outcomes = pipeline.execute(
                calls = calls,
                registry = registry,
                authorize = { call, _ -> approval.authorize(sessionId, call) },
                onSignal = { signal ->
                    when (signal) {
                        is ToolPipeline.Signal.Started -> onProgress(
                            AgentProgress.ToolStarted(
                                round = rounds,
                                id = signal.call.id,
                                name = signal.call.name,
                                argsJson = signal.call.argumentsJson,
                                startedAt = clock(),
                            ),
                        )

                        is ToolPipeline.Signal.Finished -> onProgress(
                            AgentProgress.ToolFinished(
                                id = signal.outcome.call.id,
                                state = if (signal.outcome.isError) {
                                    ev.buan.plugins.agent.api.StepState.Failed
                                } else {
                                    ev.buan.plugins.agent.api.StepState.Ok
                                },
                                elapsedMs = 0,
                                preview = signal.outcome.text.take(300),
                            ),
                        )
                    }
                },
                onAbandon = { abandoned, reason ->
                    abandoned.forEach { call ->
                        log.append(sessionId, SessionEvent.ToolAbandoned(call.id, call.name, reason))
                    }
                },
            )

            var repeated = false
            for (outcome in outcomes) {
                val call = outcome.call
                val message = AgentMessage(
                    role = AgentRole.TOOL,
                    content = outcome.text,
                    name = call.name,
                    toolCallId = call.id,
                    images = outcome.images,
                )
                log.append(sessionId, SessionEvent.Message(message))
                events += AgentEvent(AgentEvent.Kind.TOOL_RESULT, outcome.text, call.name, call.id)

                val key = "${call.name}\u0000${call.argumentsJson}"
                val previous = seen.put(key, outcome.text)
                if (previous != null && previous == outcome.text && !outcome.isError) repeated = true
            }

            if (repeated) {
                stop = AgentStop(
                    AgentStop.Reason.REPEATED_CALLS,
                    rounds,
                    toolCalls,
                    "同一工具用同样参数返回了同样的结果两次",
                )
                break
            }
        }

        val finalStop = stop ?: AgentStop(AgentStop.Reason.ERROR, rounds, toolCalls, "循环没有给出结局")
        log.append(sessionId, SessionEvent.TurnEnded(finalStop))

        val reply = AgentReply(
            message = lastAssistant ?: AgentMessage(AgentRole.ASSISTANT, ""),
            model = modelRef,
            events = events,
            usage = if (usage.isEmpty()) null else usage,
            stop = finalStop,
        )
        onProgress(AgentProgress.Done(reply))
        return reply
    }

    /**
     * 组装一次请求：系统消息（稳定层）+ 历史。
     *
     * 易变层（时间、电量这类）不放进系统消息，而是并进最后一条用户消息的末尾：
     * prefix cache 从第一个 token 开始比对，系统消息一变，后面整段历史都失效。
     */
    private suspend fun buildRequest(sessionId: String, persona: String?): List<AgentMessage> {
        val now = clock()
        val layers = runCatching { prompt?.layers(PromptContext(sessionId, now)).orEmpty() }
            .getOrElse { emptyList() }

        val stable = layers.filterNot { it.volatile }
            .sortedWith(compareByDescending<PromptLayer> { it.priority }.thenBy { it.id })
        val volatile = layers.filter { it.volatile }

        val system = buildString {
            persona?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
            stable.forEach { layer -> append("## ").append(layer.title).append('\n').append(layer.body).append("\n\n") }
        }.trim()

        val history = log.messages(sessionId).toMutableList()
        if (volatile.isNotEmpty() && history.isNotEmpty()) {
            val tail = history.removeAt(history.lastIndex)
            val extra = volatile.joinToString("\n") { it.body }
            history += tail.copy(content = tail.content + "\n\n" + extra)
        }
        return if (system.isEmpty()) history else listOf(AgentMessage(AgentRole.SYSTEM, system)) + history
    }

    private fun AgentUsage?.plus(other: AgentUsage?): AgentUsage {
        val left = this
        if (left == null) return other ?: AgentUsage()
        if (other == null) return left
        return AgentUsage(
            promptTokens = sumOrNull(left.promptTokens, other.promptTokens),
            completionTokens = sumOrNull(left.completionTokens, other.completionTokens),
            totalTokens = sumOrNull(left.totalTokens, other.totalTokens),
        )
    }

    private fun AgentUsage.isEmpty(): Boolean =
        promptTokens == null && completionTokens == null && totalTokens == null

    private fun sumOrNull(a: Int?, b: Int?): Int? = when {
        a == null && b == null -> null
        a == null -> b
        b == null -> a
        else -> a + b
    }
}
