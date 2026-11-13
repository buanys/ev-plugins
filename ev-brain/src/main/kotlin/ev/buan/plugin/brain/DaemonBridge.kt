package ev.buan.plugin.brain

import android.util.Base64
import ev.buan.plugins.agent.api.ApprovalDecision
import ev.buan.plugins.agent.api.ApprovalService
import ev.buan.plugins.agent.api.ToolRegistry
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Everything the agent process may ask the host to do.
 *
 * Three requests, three owners: the app's HTTP stack, the tool registry, and
 * the human at the approval gate. Nothing here decides anything on its own -
 * the daemon asks, the host answers, and each answer is what the model sees.
 */
internal class DaemonBridge(
    private val tools: () -> ToolRegistry?,
    private val approval: () -> ApprovalService?,
) {

    suspend fun handle(method: String, params: JSONObject): HostReply = when (method) {
        "http.request" -> http(params)
        "tool.call" -> tool(params)
        "approval.request" -> approval(params)
        else -> error("未知的宿主请求 $method")
    }

    /**
     * Runs one HTTP request with the app's own networking.
     *
     * A streamed request answers with the head immediately and keeps the body
     * open: the daemon feeds it to the model as it arrives, and the reader is
     * closed by [AgentDaemon.pump] when the body ends or fails.
     */
    private suspend fun http(params: JSONObject): HostReply = withContext(Dispatchers.IO) {
        val connection = URL(params.getString("url")).openConnection() as HttpURLConnection
        connection.requestMethod = params.optString("method", "GET")
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true

        params.optJSONObject("headers")?.let { headers ->
            for (name in headers.keys()) {
                connection.setRequestProperty(name, headers.optString(name))
            }
        }

        val body = params.optString("body_base64").takeIf { it.isNotEmpty() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        if (body != null && body.isNotEmpty()) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }

        val status = connection.responseCode
        val headers = JSONObject()
        connection.headerFields.forEach { (name, values) ->
            if (name != null && !values.isNullOrEmpty()) headers.put(name, values.joinToString(", "))
        }
        val head = JSONObject().put("status", status).put("headers", headers)

        if (params.optBoolean("stream", false)) {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) {
                connection.disconnect()
                return@withContext HostReply.Json(head.put("body_base64", ""))
            }
            return@withContext HostReply.Streamed(head, stream, connection)
        }

        val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        HostReply.Json(
            head.put("body_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )
    }

    private suspend fun tool(params: JSONObject): HostReply {
        val name = params.getString("tool_name")
        val arguments = params.optString("arguments_json", "{}")
        val tool = tools()?.lookup(name) ?: error("没有插件提供 $name")
        val result = tool.invokeResult(arguments)
        val images = org.json.JSONArray()
        for (image in result.images) {
            images.put(
                JSONObject()
                    .put("mime_type", image.mimeType)
                    .put("base64", image.base64),
            )
        }
        return HostReply.Json(JSONObject().put("text", result.text).put("images", images))
    }

    private suspend fun approval(params: JSONObject): HostReply {
        val name = params.getString("tool_name")
        val arguments = params.optString("arguments_json", "{}")
        val gate = approval()
        val decision = if (gate == null) {
            ApprovalDecision.UNAVAILABLE
        } else {
            runCatching { gate.request(name, arguments) }.getOrElse { ApprovalDecision.UNAVAILABLE }
        }
        val wire = when (decision) {
            ApprovalDecision.ALLOW_ONCE -> "allow_once"
            ApprovalDecision.ALLOW_SESSION -> "allow_session"
            ApprovalDecision.DENY -> "deny"
            ApprovalDecision.UNAVAILABLE -> "unavailable"
        }
        return HostReply.Json(JSONObject().put("decision", wire))
    }
}
