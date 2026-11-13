package ev.buan.plugin.brain

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** What the host answers one reverse request with. */
internal sealed interface HostReply {
    /** A complete answer. */
    data class Json(val body: JSONObject) : HostReply

    /**
     * A response head plus a body that is still arriving.
     *
     * [connection] is kept so the reader can be closed (and so an abandoned
     * stream does not leak a socket).
     */
    data class Streamed(
        val head: JSONObject,
        val body: InputStream,
        val connection: HttpURLConnection,
    ) : HostReply
}

/**
 * The agent process and the JSON-lines channel in front of it.
 *
 * Commands go out with numeric ids; the daemon's own requests come back with
 * string ids (`h1`, `h2`, …). A response is routed by the type of its id, which
 * is why the two spaces never mix.
 *
 * One instance per process. When the process dies the pending commands fail and
 * [events] stops; the caller restarts it (see [AgentDaemon.start]).
 */
internal class AgentDaemon(
    binary: File,
    private val scope: CoroutineScope,
    private val onHostRequest: suspend (String, JSONObject) -> HostReply,
) {

    private val process: Process = ProcessBuilder(binary.absolutePath)
        // stderr carries logs only; it is drained so the pipe cannot fill up.
        .redirectErrorStream(false)
        .start()

    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(0)

    /** Every `event` notification, in the order the daemon produced it. */
    val events = MutableSharedFlow<JSONObject>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val alive: Boolean get() = process.isAlive

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { readErrors() }
    }

    /** `hello` plus the protocol version the daemon speaks. */
    suspend fun hello(): JSONObject = call("hello", JSONObject(), timeoutMs = 10_000)

    /** Sends a command and waits for its answer. */
    suspend fun call(method: String, params: JSONObject, timeoutMs: Long = 30_000): JSONObject {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id.toString()] = deferred
        try {
            send(
                JSONObject()
                    .put("id", id)
                    .put("method", method)
                    .put("params", params)
                    .toString(),
            )
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id.toString())
        }
    }

    fun stop() {
        runCatching { process.destroy() }
    }

    private suspend fun send(line: String) {
        writeLock.withLock {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    private suspend fun readLoop() {
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (message.has("method") && message.has("id")) {
                    val id = message.getString("id")
                    val method = message.getString("method")
                    val params = message.optJSONObject("params") ?: JSONObject()
                    scope.launch(Dispatchers.IO) { answer(id, method, params) }
                    continue
                }
                if (message.has("id")) {
                    pending.remove(message.getString("id"))?.complete(message)
                    continue
                }
                events.emit(message.optJSONObject("params") ?: JSONObject())
            }
        } catch (error: IOException) {
            // The process went away mid-read; the failure is reported below.
        } finally {
            val failure = IOException("agent 进程已退出")
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
        }
    }

    private fun readErrors() {
        val reader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            android.util.Log.i("EV", "agentd: $line")
        }
    }

    private suspend fun answer(id: String, method: String, params: JSONObject) {
        val reply = runCatching { onHostRequest(method, params) }
        reply.fold(
            onSuccess = { answer ->
                when (answer) {
                    is HostReply.Json -> send(result(id, answer.body))
                    is HostReply.Streamed -> {
                        send(result(id, answer.head))
                        scope.launch(Dispatchers.IO) { pump(id, answer) }
                    }
                }
            },
            onFailure = { error -> send(failure(id, error)) },
        )
    }

    /** Feeds one streaming body back as `http.chunk` … `http.end`. */
    private suspend fun pump(id: String, answer: HostReply.Streamed) {
        var errorText: String? = null
        try {
            answer.body.use { body ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read <= 0) break
                    val chunk = String(
                        android.util.Base64.encode(buffer, 0, read, android.util.Base64.NO_WRAP),
                        Charsets.US_ASCII,
                    )
                    send(
                        JSONObject()
                            .put("method", "http.chunk")
                            .put(
                                "params",
                                JSONObject().put("request_id", id).put("data_base64", chunk),
                            )
                            .toString(),
                    )
                }
            }
        } catch (failure: IOException) {
            errorText = failure.message ?: "读取响应体失败"
        } finally {
            runCatching { answer.connection.disconnect() }
            send(
                JSONObject()
                    .put("method", "http.end")
                    .put(
                        "params",
                        JSONObject()
                            .put("request_id", id)
                            .apply { errorText?.let { put("error", it) } },
                    )
                    .toString(),
            )
        }
    }

    private fun result(id: String, body: JSONObject): String =
        JSONObject().put("id", id).put("result", body).toString()

    private fun failure(id: String, error: Throwable): String = JSONObject()
        .put("id", id)
        .put(
            "error",
            JSONObject()
                .put("code", "host_failed")
                .put("message", error.message ?: error::class.simpleName ?: "宿主处理失败"),
        )
        .toString()

    companion object {
        /**
         * Extracts the bundled daemon next to the jar and starts it.
         *
         * The container ships `bin/ev-agentd`; the host stores the container as
         * `mods/<id>.jar`, so the bytes are read back out of that jar and
         * written 0755 into the plugin's own storage. Rewritten whenever the
         * size changes, which is enough to pick up a new build.
         */
        fun installBinary(jar: File, target: File): File {
            if (target.isFile) return target
            target.parentFile?.mkdirs()
            java.util.zip.ZipFile(jar).use { zip ->
                val entry = zip.getEntry("bin/ev-agentd") ?: error("插件容器里没有 bin/ev-agentd")
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target.setExecutable(true, false)
            return target
        }
    }
}
