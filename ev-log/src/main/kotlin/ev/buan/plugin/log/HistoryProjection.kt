package ev.buan.plugin.log

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.SessionEvent

/**
 * 从事件流派生模型可见消息。
 *
 * 纯函数：同样的事件序列永远得到同样的消息序列。回放就是重新派生，不是另跑一遍。
 * 因此这个文件里不许有 IO、不许读时钟、不许看外部状态。
 *
 * ## 两条规则
 *
 * 1. **只有 [SessionEvent.inModelContext] 的事件进历史**。轮次边界、审批审计只进日志。
 * 2. **被放弃的工具调用要变成一条失败结果**。模型请求过这个工具，如果什么都不给它，
 *    下一轮它会以为自己没调用过而重复调用；给一条明确的 "Error: …"，它才知道这一步失败了。
 *
 * 第 2 条就是"补洞"：崩溃恢复时不改写历史，而是追加一条 [SessionEvent.ToolAbandoned]，
 * 投影时它自动变成模型能看懂的失败结果。
 */
internal object HistoryProjection {

    /** 模型下一轮看到的完整历史，最旧在前。 */
    fun messages(events: List<SessionEvent>): List<AgentMessage> {
        val answered = answeredCallIds(events)
        return buildList {
            for (event in events) {
                when (event) {
                    is SessionEvent.Message -> add(event.message)
                    is SessionEvent.ToolAbandoned ->
                        // 已经有真实结果的，就不要合成一条假的盖在它上面。
                        if (event.callId !in answered) add(abandonedMessage(event))
                    else -> Unit
                }
            }
        }
    }

    /**
     * 还没有结果、也没有被标记放弃的工具调用。
     *
     * 恢复时用它决定要补哪些洞：返回的是 (callId, 工具名)，顺序按模型请求的顺序。
     */
    fun unansweredCalls(events: List<SessionEvent>): List<AgentToolCall> {
        val answered = answeredCallIds(events)
        val abandoned = events.filterIsInstance<SessionEvent.ToolAbandoned>().map { it.callId }.toSet()
        return events
            .filterIsInstance<SessionEvent.Message>()
            .flatMap { it.message.toolCalls }
            .filter { it.id !in answered && it.id !in abandoned }
            .distinctBy { it.id }
    }

    /** 已经拿到结果的调用：真实的 tool 消息，或一条放弃记录。 */
    private fun answeredCallIds(events: List<SessionEvent>): Set<String> = buildSet {
        events.filterIsInstance<SessionEvent.Message>()
            .map { it.message }
            .filter { it.role == AgentRole.TOOL }
            .mapNotNullTo(this) { it.toolCallId }
    }

    private fun abandonedMessage(event: SessionEvent.ToolAbandoned): AgentMessage = AgentMessage(
        role = AgentRole.TOOL,
        name = event.toolName,
        toolCallId = event.callId,
        content = when (event.reason) {
            // 还没开始跑：重试是安全的，所以措辞鼓励重试。
            AbandonReason.NOT_STARTED ->
                "Error: 工具没有执行（$SESSION_ABORTED）。可以安全重试。"

            // 已经跑过但不知道结果：先说不知道，再说重试前要核实。
            AbandonReason.OUTCOME_UNKNOWN ->
                "Error: 工具结果未知（$SESSION_ABORTED）。它可能已经执行过；" +
                    "如果要重试，先核实外部状态。"
        },
    )

    private const val SESSION_ABORTED = "进程中断"
}
