package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalOutcome
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * 危险工具的审批链：问人、记账、给出"跑不跑"的答案。
 *
 * ## 三条规则
 *
 * 1. **fail-closed**。拿不到明确的"允许"，一律不跑。没有审批门、门抛错、门的回答不可解析，
 *    结局都是 [ApprovalOutcome.UNAVAILABLE]，等价于拒绝。这条比"默认放行"重要得多——
 *    一个装错插件导致审批门缺失的部署，不该变成"所有危险操作都能执行"。
 * 2. **审计成对**。问之前写 [SessionEvent.ApprovalAsked]，答之后写 [SessionEvent.ApprovalDecided]。
 *    只有 asked 没有 decided，就是"等人回答时进程死了"的证据，恢复时看得见。
 * 3. **取消要透传**。用户按了停止，那条取消必须穿过审批继续向上抛，不能被当成"审批失败"吞掉。
 *
 * ## 关于 ALLOW_SESSION
 *
 * 审批门（`ev-ui`）自己决定"本次会话内都允许"的作用域有多长；这里只负责如实记录。
 * 记录下来的 [ApprovalOutcome.ALLOWED_SESSION] 可以让审计回答"哪一次批准覆盖了这一次"。
 */
internal class ApprovalChain(
    private val log: SessionLog?,
    private val gate: () -> ApprovalService?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 审计写失败的落点。默认打 logcat；JVM 单测里换成空实现（android.util.Log 未 mock）。 */
    private val onAuditFailure: (Throwable) -> Unit = { error ->
        android.util.Log.w("EV", "ev-brain: 审批审计写入失败", error)
    },
) {

    /** 返回拒绝理由；null 表示放行。 */
    suspend fun authorize(sessionId: String, call: AgentToolCall): String? {
        val id = newId()
        record(sessionId, SessionEvent.ApprovalAsked(id, call.name, call.id))

        val outcome = ask(call)
        record(sessionId, SessionEvent.ApprovalDecided(id, outcome))

        return when (outcome) {
            ApprovalOutcome.ALLOWED_ONCE, ApprovalOutcome.ALLOWED_SESSION -> null
            ApprovalOutcome.REJECTED -> "用户拒绝了这个操作（${call.name}）"
            ApprovalOutcome.UNAVAILABLE ->
                "拿不到审批（没有审批门或审批失败），按规则不执行 ${call.name}"
        }
    }

    private suspend fun ask(call: AgentToolCall): ApprovalOutcome = try {
        val service = gate()
        if (service == null) {
            ApprovalOutcome.UNAVAILABLE
        } else {
            when (service.request(call.name, call.argumentsJson)) {
                ApprovalDecision.ALLOW_ONCE -> ApprovalOutcome.ALLOWED_ONCE
                ApprovalDecision.ALLOW_SESSION -> ApprovalOutcome.ALLOWED_SESSION
                ApprovalDecision.DENY -> ApprovalOutcome.REJECTED
                ApprovalDecision.UNAVAILABLE -> ApprovalOutcome.UNAVAILABLE
            }
        }
    } catch (cancellation: CancellationException) {
        // 用户按了停止：这不是"审批失败"，取消要原样往上走。
        throw cancellation
    } catch (error: Throwable) {
        // 门自己坏了。问题要往"关闭"的方向失败，而不是让调用漏过去。
        ApprovalOutcome.UNAVAILABLE
    }

    /**
     * 写审计。日志只是观察面：它坏了不该让安全决策跟着坏，
     * 所以这里吞掉异常并留一行日志——**决策结果已经定了**。
     */
    private suspend fun record(sessionId: String, event: SessionEvent) {
        val target = log ?: return
        runCatching { target.append(sessionId, event) }.onFailure(onAuditFailure)
    }
}
