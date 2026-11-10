package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.cordis.api.ServiceDirectory

/**
 * [AgentService] 的实现：把 loop 需要的六个服务**在每次调用时现查**。
 *
 * 为什么不在 `apply` 里查一次存起来：服务目录是动态的。`ev-model` 可能晚于本插件启动，
 * 也可能被停用再启用；把 null 缓存下来，会让"模型刚连上却仍然说没连上"变成常驻故障。
 * 每次查一次表是便宜的，代价只是几次 map 读。
 *
 * 缺服务时**不抛异常**：契约要求 `ready=false` 时仍能给出一个解释性的回复，
 * 否则一个没装模型插件的部署，界面上看到的会是崩溃而不是"还没连上模型"。
 */
internal class BrainService(
    private val directory: ServiceDirectory,
    private val limits: AgentLoop.Limits = AgentLoop.Limits(),
    private val maxParallel: Int = ToolPipeline.DEFAULT_MAX_PARALLEL,
) : AgentService {

    private val log: SessionLog? get() = directory.lookup(SessionLog.Key)
    private val registry: ToolRegistry? get() = directory.lookup(ToolRegistry.Key)
    private val models: ModelService? get() = directory.lookup(ModelService.Key)
    private val prompt: SystemPromptService? get() = directory.lookup(SystemPromptService.Key)
    private val gate: ApprovalService? get() = directory.lookup(ApprovalService.Key)

    override val ready: Boolean
        get() = models?.models?.isNotEmpty() == true && log != null

    override suspend fun send(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): AgentReply {
        val prepared = prepare()
        if (prepared is Preparation.NotReady) return notConnected(prepared.reason)
        val ready = prepared as Preparation.Ready
        val ref = model ?: ready.models.first()
        return try {
            ready.loop.run(sessionId, text, ref, systemPrompt?.takeIf { it.isNotBlank() }, onProgress = {})
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            failed(error)
        }
    }

    override fun sendStreaming(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): Flow<AgentProgress> = flow {
        val prepared = prepare()
        if (prepared is Preparation.NotReady) {
            emit(AgentProgress.Done(notConnected(prepared.reason)))
            return@flow
        }
        val ready = prepared as Preparation.Ready
        val ref = model ?: ready.models.first()
        try {
            ready.loop.run(sessionId, text, ref, systemPrompt?.takeIf { it.isNotBlank() }) { progress ->
                emit(progress)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            emit(AgentProgress.Done(failed(error)))
        }
    }

    /**
     * 能不能跑，以及缺什么——**一个类型说清一件事**。
     *
     * 以前这里返回 `AgentLoop?`，缺什么由另一个 `missingReason()` 再判一次。
     * 同一份知识编码在两个地方，改一处忘另一处就会给出错误的解释。
     */
    private sealed interface Preparation {
        data class Ready(val loop: AgentLoop, val models: List<ModelRef>) : Preparation
        data class NotReady(val reason: String) : Preparation
    }

    /** 六个依赖里缺任何一个都不组 loop：半套依赖跑起来只会产出错误的历史。 */
    private fun prepare(): Preparation {
        val log = log ?: return Preparation.NotReady("事件流插件（ev-log）没启动")
        val model = models ?: return Preparation.NotReady("模型插件（ev-model）没启动")
        if (model.models.isEmpty()) return Preparation.NotReady("模型插件还没有配置任何模型")

        val loop = AgentLoop(
            log = log,
            registry = registry,
            model = model,
            prompt = prompt,
            approval = ApprovalChain(log = log, gate = { gate }),
            pipeline = ToolPipeline(maxParallel),
            limits = limits,
        )
        return Preparation.Ready(loop, model.models)
    }

    /** 契约要求：不 ready 也要答一句人话，调用方忽略 [ready] 也不该看到崩溃。 */
    private fun notConnected(detail: String): AgentReply = AgentReply(
        message = AgentMessage(AgentRole.ASSISTANT, "还没连上：$detail。"),
        stop = AgentStop(AgentStop.Reason.ERROR, rounds = 0, toolCalls = 0, detail = detail),
    )

    private fun failed(error: Throwable): AgentReply {
        val detail = error.message ?: error::class.simpleName ?: "未知错误"
        return AgentReply(
            message = AgentMessage(AgentRole.ASSISTANT, "这一轮失败了：$detail"),
            stop = AgentStop(AgentStop.Reason.ERROR, rounds = 0, toolCalls = 0, detail = detail),
        )
    }
}
