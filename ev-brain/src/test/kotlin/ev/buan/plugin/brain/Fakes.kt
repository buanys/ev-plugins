package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog
import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolResult
import ev.buan.plugins.agent.api.ToolSpec
import kotlinx.coroutines.delay

/** 测试替身集中在这里，避免每个测试各写一遍。 */

internal fun toolSpec(
    name: String,
    dangerous: Boolean = false,
    parallelSafe: Boolean = false,
) = ToolSpec(
    name = name,
    description = "测试工具 $name",
    parametersJson = """{"type":"object","properties":{}}""",
    dangerous = dangerous,
    parallelSafe = parallelSafe,
)

internal class FakeTool(
    override val spec: ToolSpec,
    private val body: suspend (String) -> String = { "ok" },
) : Tool {
    var invocations = 0
        private set

    override suspend fun invoke(argumentsJson: String): String = invokeResult(argumentsJson).text

    override suspend fun invokeResult(argumentsJson: String): ToolResult {
        invocations++
        return ToolResult(text = body(argumentsJson))
    }
}

internal class FakeRegistry(private val tools: List<Tool>) : ToolRegistry {
    override fun specs(): List<ToolSpec> = tools.map { it.spec }
    override fun lookup(name: String): Tool? = tools.firstOrNull { it.spec.name == name }
    override fun register(owner: String, tools: List<Tool>) = Unit
    override fun unregister(owner: String) = Unit
}

internal class FakeLog : SessionLog {
    val events = mutableListOf<SessionEvent>()

    override suspend fun append(sessionId: String, event: SessionEvent) {
        events += event
    }

    override suspend fun messages(sessionId: String): List<AgentMessage> =
        HistoryProjection.messages(events)

    override suspend fun events(sessionId: String): List<SessionEvent> = events.toList()

    override suspend fun clear(sessionId: String) {
        events.clear()
    }

    /** 只是为了让测试读起来顺：模型可见的消息内容。 */
    fun contents(): List<String> = events
        .filterIsInstance<SessionEvent.Message>()
        .map { it.message.content }
}

/**
 * 照 ev-log 的投影规则重算，而不是自己 another 一套——测试要验证的是"主循环写进去的东西
 * 能不能被投影成正确历史"，用被测插件自己的规则才算数。
 */
internal object HistoryProjection {
    fun messages(events: List<SessionEvent>): List<AgentMessage> = buildList {
        val answered = events.filterIsInstance<SessionEvent.Message>()
            .map { it.message }
            .filter { it.toolCallId != null }
            .mapNotNull { it.toolCallId }
            .toSet()
        for (event in events) {
            when (event) {
                is SessionEvent.Message -> add(event.message)
                is SessionEvent.ToolAbandoned ->
                    if (event.callId !in answered) {
                        add(AgentMessage(ev.buan.plugins.agent.api.AgentRole.TOOL, "Error: 放弃", toolCallId = event.callId, name = event.toolName))
                    }
                else -> Unit
            }
        }
    }
}

/** 按顺序吐预设回复的模型。用完还在被调用就返回最后一条。 */
internal class FakeModel(
    private val replies: List<AgentReply>,
    private val delayMs: Long = 0,
) : ModelService {
    override val models: List<ModelRef> = listOf(ModelRef("fake", "fake-1"))
    var calls = 0
        private set

    override suspend fun complete(
        model: ModelRef,
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
    ): AgentReply {
        if (delayMs > 0) delay(delayMs)
        val reply = replies.getOrElse(calls) { replies.last() }
        calls++
        return reply
    }
}

internal class FakeGate(
    private val answer: suspend (String, String) -> ApprovalDecision,
) : ApprovalService {
    var asks = 0
        private set

    override suspend fun request(toolName: String, argumentsJson: String): ApprovalDecision {
        asks++
        return answer(toolName, argumentsJson)
    }
}

/** 一次工具调用的助手回复。 */
internal fun assistantWithCalls(vararg calls: AgentToolCall): AgentReply = AgentReply(
    message = AgentMessage(
        role = ev.buan.plugins.agent.api.AgentRole.ASSISTANT,
        content = "",
        toolCalls = calls.toList(),
    ),
    model = ModelRef("fake", "fake-1"),
)

internal fun assistantText(text: String): AgentReply = AgentReply(
    message = AgentMessage(ev.buan.plugins.agent.api.AgentRole.ASSISTANT, text),
    model = ModelRef("fake", "fake-1"),
)
