package ev.buan.plugin.log

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentRole
import ev.buan.plugins.agent.api.AgentStop
import ev.buan.plugins.agent.api.AgentToolCall
import ev.buan.plugins.agent.api.ApprovalOutcome
import ev.buan.plugins.agent.api.SessionEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 事件与 JSONL 行之间的唯一转换点。
 *
 * 一条事件一行，字段平铺，不用嵌套信封：出问题时 `tail` 一条就是完整事实，
 * 不需要先读 schema 才知道它是什么。
 *
 * ## 两条取舍
 *
 * 1. **图片不落盘**。`AgentMessage.images` 是 MB 级的瞬态数据，写进日志一天就能吃光预算；
 *    模型随时可以重新截图。重建出来的消息因此只有文字——这一点与契约里的说明一致。
 * 2. **解析失败返回 null，不抛**。一行坏掉不该让整个会话读不出来；调用方统计坏行数，
 *    并在返回的结果里如实说明（见 [EventStore.read]）。
 */
internal object EventCodec {

    private const val TYPE = "type"
    private const val AT = "at"

    fun encode(event: SessionEvent): String = JSONObject().apply {
        put(AT, event.at)
        when (event) {
            is SessionEvent.Message -> {
                put(TYPE, "message")
                put("message", messageJson(event.message))
            }

            is SessionEvent.TurnEnded -> {
                put(TYPE, "turn_end")
                put("reason", event.stop.reason.name)
                put("rounds", event.stop.rounds)
                put("toolCalls", event.stop.toolCalls)
                event.stop.detail?.let { put("detail", it) }
            }

            is SessionEvent.ApprovalAsked -> {
                put(TYPE, "approval_asked")
                put("id", event.id)
                put("tool", event.toolName)
                event.callId?.let { put("callId", it) }
            }

            is SessionEvent.ApprovalDecided -> {
                put(TYPE, "approval_decided")
                put("id", event.id)
                put("outcome", event.outcome.name)
            }

            is SessionEvent.ToolAbandoned -> {
                put(TYPE, "tool_abandoned")
                put("callId", event.callId)
                put("tool", event.toolName)
                put("reason", event.reason.name)
            }
        }
    }.toString()

    /** 解析一行；不认识或残缺都返回 null，由调用方决定怎么记账。 */
    fun decode(line: String): SessionEvent? = runCatching {
        val json = JSONObject(line)
        val at = json.getLong(AT)
        when (json.getString(TYPE)) {
            "message" -> SessionEvent.Message(messageOf(json.getJSONObject("message")), at)

            "turn_end" -> SessionEvent.TurnEnded(
                stop = AgentStop(
                    reason = AgentStop.Reason.valueOf(json.getString("reason")),
                    rounds = json.optInt("rounds", 0),
                    toolCalls = json.optInt("toolCalls", 0),
                    detail = json.optString("detail").takeIf { it.isNotEmpty() },
                ),
                at = at,
            )

            "approval_asked" -> SessionEvent.ApprovalAsked(
                id = json.getString("id"),
                toolName = json.getString("tool"),
                callId = json.optString("callId").takeIf { it.isNotEmpty() },
                at = at,
            )

            "approval_decided" -> SessionEvent.ApprovalDecided(
                id = json.getString("id"),
                outcome = ApprovalOutcome.valueOf(json.getString("outcome")),
                at = at,
            )

            "tool_abandoned" -> SessionEvent.ToolAbandoned(
                callId = json.getString("callId"),
                toolName = json.getString("tool"),
                reason = AbandonReason.valueOf(json.getString("reason")),
                at = at,
            )

            // 新版本写下的、这个版本还不认识的类型：跳过，不整条会话失败。
            else -> null
        }
    }.getOrNull()

    private fun messageJson(message: AgentMessage): JSONObject = JSONObject().apply {
        put("role", message.role.name)
        put("content", message.content)
        message.name?.let { put("name", it) }
        message.toolCallId?.let { put("toolCallId", it) }
        if (message.toolCalls.isNotEmpty()) {
            put(
                "toolCalls",
                JSONArray().apply {
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject().apply {
                                put("id", call.id)
                                put("name", call.name)
                                put("argumentsJson", call.argumentsJson)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun messageOf(json: JSONObject): AgentMessage {
        val calls = json.optJSONArray("toolCalls")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val call = array.getJSONObject(index)
                    add(
                        AgentToolCall(
                            id = call.getString("id"),
                            name = call.getString("name"),
                            argumentsJson = call.optString("argumentsJson", "{}"),
                        ),
                    )
                }
            }
        }.orEmpty()

        return AgentMessage(
            role = AgentRole.valueOf(json.getString("role")),
            content = json.optString("content"),
            name = json.optString("name").takeIf { it.isNotEmpty() },
            toolCallId = json.optString("toolCallId").takeIf { it.isNotEmpty() },
            toolCalls = calls,
        )
    }
}
