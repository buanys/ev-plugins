package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentProgress
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentService
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.PromptContext
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.StepState
import ev.buan.plugins.agent.api.SystemPromptService
import ev.buan.plugins.agent.api.ToolRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.json.JSONArray
import org.json.JSONObject

/**
 * [AgentService] on top of the agent process.
 *
 * The loop lives in `ev-agentd`; this class is the host half of the contract:
 * it hands the process a provider, the stored history and the tool list, then
 * turns the process's events back into [AgentProgress] and writes the turn into
 * [SessionLog].
 *
 * Services are looked up on every call, never cached: `ev-model` may start
 * after this plugin, and a cached `null` turns "just connected" into a
 * permanent "not connected".
 */
internal class BrainService(
    private val directory: ServiceDirectory,
    private val storage: PluginStorage?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AgentService {

    private val log: SessionLog? get() = directory.lookup(SessionLog.Key)
    private val registry: ToolRegistry? get() = directory.lookup(ToolRegistry.Key)
    private val providers: ModelProviderService? get() = directory.lookup(ModelProviderService.Key)
    private val prompt: SystemPromptService? get() = directory.lookup(SystemPromptService.Key)

    private var daemon: AgentDaemon? = null
    private val startLock = Mutex()

    override val ready: Boolean
        get() = configuredProvider() != null && log != null && storage != null

    override suspend fun send(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): AgentReply {
        var reply: AgentReply? = null
        sendStreaming(sessionId, text, model, systemPrompt).collect { progress ->
            if (progress is AgentProgress.Done) reply = progress.reply
        }
        return reply ?: failed("这一轮没有给出结果")
    }

    override fun sendStreaming(
        sessionId: String,
        text: String,
        model: ModelRef?,
        systemPrompt: String?,
    ): Flow<AgentProgress> = flow {
        val provider = configuredProvider()
        if (provider == null) {
            emit(AgentProgress.Done(notConnected("模型插件还没有可用端点")))
            return@flow
        }
        val session = log ?: run {
            emit(AgentProgress.Done(notConnected("事件流插件（ev-log）没启动")))
            return@flow
        }

        val wireModel = model?.model
            ?: provider.selectedModelId
            ?: provider.models.firstOrNull()?.id
            ?: run {
                emit(AgentProgress.Done(notConnected("这个端点没有选模型")))
                return@flow
            }

        val params = JSONObject()
            .put("run_id", "r-" + UUID.randomUUID())
            .put("provider", providerParams(provider))
            .put("model", wireModel)
            .put("prompt", text)
            .put("history", history(session, sessionId))
            .put("tools", tools())
            .put("max_turns", 16)
        preamble(sessionId, systemPrompt)?.let { params.put("preamble", it) }

        val runId = params.getString("run_id")
        val process = try {
            daemon()
        } catch (error: Throwable) {
            emit(AgentProgress.Done(failed("agent 进程起不来：${error.message ?: error::class.simpleName}")))
            return@flow
        }

        val queue = Channel<JSONObject>(Channel.UNLIMITED)
        val relay = scope.launch {
            process.events.collect { event ->
                if (event.optString("run_id") == runId) queue.send(event)
            }
        }

        val startedAt = System.currentTimeMillis()
        var callIndex = 0
        var finalText = ""
        try {
            process.call("turn", params, timeoutMs = 15_000)
            while (true) {
                val event = queue.receive()
                when (event.optString("kind")) {
                    "text" -> emit(AgentProgress.TextDelta(1, event.optString("text")))
                    "tool_call" -> {
                        callIndex += 1
                        emit(
                            AgentProgress.ToolStarted(
                                round = 1,
                                id = "${event.optString("tool_name")}#$callIndex",
                                name = event.optString("tool_name"),
                                argsJson = event.optString("arguments_json"),
                                startedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    "tool_result" -> emit(
                        AgentProgress.ToolFinished(
                            id = "${event.optString("tool_name")}#$callIndex",
                            state = if (event.optBoolean("refused")) StepState.Failed else StepState.Ok,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            preview = event.optString("text").take(300),
                        ),
                    )
                    "done" -> {
                        finalText = event.optString("text")
                        break
                    }
                }
            }
        } catch (error: Throwable) {
            relay.cancel()
            emit(AgentProgress.Done(failed(error.message ?: "这一轮失败了")))
            return@flow
        }
        relay.cancel()

        val reply = AgentReply(
            message = AgentMessage(AgentRole.ASSISTANT, finalText),
            model = ModelRef(provider.id, wireModel),
            stop = AgentStop(AgentStop.Reason.FINISHED, rounds = 1, toolCalls = callIndex),
        )
        runCatching {
            session.append(sessionId, SessionEvent.Message(AgentMessage(AgentRole.USER, text)))
            session.append(sessionId, SessionEvent.Message(reply.message))
            reply.stop?.let { session.append(sessionId, SessionEvent.TurnEnded(it)) }
        }
        emit(AgentProgress.Done(reply))
    }

    /** The first endpoint that can actually answer: enabled, keyed, with a model. */
    private fun configuredProvider() = providers
        ?.providers()
        ?.firstOrNull { provider ->
            provider.enabled &&
                provider.apiKey.isNotBlank() &&
                (provider.selectedModelId != null || provider.models.isNotEmpty())
        }

    private fun providerParams(provider: ev.buan.plugins.agent.api.ModelProvider) = JSONObject()
        .put("base_url", provider.baseUrl)
        .put("api_key", provider.apiKey)
        .put("headers", JSONObject(provider.headers as Map<*, *>))

    private suspend fun history(session: SessionLog, sessionId: String): JSONArray {
        val array = JSONArray()
        session.messages(sessionId).forEach { message ->
            val role = when (message.role) {
                AgentRole.USER -> "user"
                AgentRole.ASSISTANT -> "assistant"
                else -> null
            } ?: return@forEach
            if (message.content.isBlank()) return@forEach
            array.put(JSONObject().put("role", role).put("text", message.content))
        }
        return array
    }

    private fun tools(): JSONArray {
        val array = JSONArray()
        registry?.specs()?.forEach { spec ->
            array.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", runCatching { JSONObject(spec.parametersJson) }.getOrNull())
                    .put("dangerous", spec.dangerous),
            )
        }
        return array
    }

    /** Role instructions for this turn only: the caller's, else the prompt plugin's. */
    private fun preamble(sessionId: String, systemPrompt: String?): String? {
        systemPrompt?.takeIf { it.isNotBlank() }?.let { return it }
        val service = prompt ?: return null
        val layers = runCatching {
            service.layers(PromptContext(sessionId, System.currentTimeMillis()))
        }.getOrDefault(emptyList())
        if (layers.isEmpty()) return null
        return layers
            .sortedWith(compareByDescending<ev.buan.plugins.agent.api.PromptLayer> { it.priority }
                .thenBy { it.id })
            .joinToString("\n\n") { it.body }
            .takeIf { it.isNotBlank() }
    }

    private suspend fun daemon(): AgentDaemon = startLock.withLock {
        daemon?.takeIf { it.alive }?.let { return@withLock it }
        val area = storage ?: error("插件存储不可用")
        val target = File(File(area.rootPath()), "bin/ev-agentd")
        val binary = AgentDaemon.installBinary(container(), target)
        val bridge = DaemonBridge(
            tools = { directory.lookup(ToolRegistry.Key) },
            approval = { directory.lookup(ev.buan.plugins.agent.api.ApprovalService.Key) },
        )
        val created = AgentDaemon(binary, scope) { method, params -> bridge.handle(method, params) }
        created.hello()
        daemon = created
        created
    }

    /**
     * The container this plugin was loaded from.
     *
     * The host stores every plugin as `mods/<id>.jar`; the id is fixed by the
     * manifest, so no lookup service is needed. A missing file is a real error:
     * without the container there is no bundled daemon to extract.
     */
    private fun container(): File {
        val area = storage ?: error("插件存储不可用")
        val root = File(area.rootPath())
        val parent = root.parentFile ?: root
        val candidates = listOf(
            File(parent, "${AgentBrainPlugin.ID}.jar"),
            File("/data/data/ev.buan/files/mods/${AgentBrainPlugin.ID}.jar"),
        )
        return candidates.firstOrNull { it.isFile } ?: error("找不到插件容器 ${AgentBrainPlugin.ID}.jar")
    }

    private fun notConnected(detail: String): AgentReply = AgentReply(
        message = AgentMessage(AgentRole.ASSISTANT, "还没连上：$detail。"),
        stop = AgentStop(AgentStop.Reason.ERROR, rounds = 0, toolCalls = 0, detail = detail),
    )

    private fun failed(detail: String): AgentReply = AgentReply(
        message = AgentMessage(AgentRole.ASSISTANT, "这一轮失败了：$detail"),
        stop = AgentStop(AgentStop.Reason.ERROR, rounds = 0, toolCalls = 0, detail = detail),
    )
}
