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
        val loop = prepare() ?: return notConnected()
        val ref = model ?: models?.models?.firstOrNull() ?: return notConnected("模型列表还是空的")
        return try {
            loop.run(sessionId, text, ref, systemPrompt?.takeIf { it.isNotBlank() }, onProgress = {})
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
        val loop = prepare()
        if (loop == null) {
            emit(AgentProgress.Done(notConnected()))
            return@flow
        }
        val ref = model ?: models?.models?.firstOrNull()
        if (ref == null) {
            emit(AgentProgress.Done(notConnected("模型列表还是空的")))
            return@flow
        }
        try {
            loop.run(sessionId, text, ref, systemPrompt?.takeIf { it.isNotBlank() }) { progress ->
                emit(progress)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            emit(AgentProgress.Done(failed(error)))
        }
    }

    /** 六个依赖里缺任何一个都不组 loop：半套依赖跑起来只会产出错误的历史。 */
    private fun prepare(): AgentLoop? {
        val log = log ?: return null
        val model = models ?: return null
        return AgentLoop(
            log = log,
            registry = registry,
            model = model,
            prompt = prompt,
            approval = ApprovalChain(log = log, gate = { gate }),
            pipeline = ToolPipeline(maxParallel),
            limits = limits,
        )
    }

    private fun missingReason(): String = when {
        log == null -> "事件流插件（ev-log）没启动"
        models == null -> "模型插件（ev-model）没启动"
        models?.models?.isEmpty() == true -> "模型插件还没有配置任何模型"
        else -> "依赖没有就绪"
    }

    /** 契约要求：不 ready 也要答一句人话，调用方忽略 [ready] 也不该看到崩溃。 */
    private fun notConnected(detail: String = missingReason()): AgentReply = AgentReply(
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
