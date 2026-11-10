package ev.buan.plugin.log

import java.io.File
import java.io.RandomAccessFile

/**
 * 每个会话一个 append-only 的 JSONL 文件。
 *
 * 照抄 DeepSeek Harness 落盘的四条纪律（它写这段话是因为真的踩过）：
 *
 * 1. **单写者**：同一会话的写入串行化，序号就是行号，不需要额外分配。
 * 2. **每批 fsync**：`append` 返回即表示已经落盘。不 fsync 的"写成功"在进程被杀时是空话。
 * 3. **写失败回滚到原长度**：半行比没有更糟——读的时候它会被当成一条坏记录。
 * 4. **尾部半行不算数**：崩溃可能留下没有换行符的残行，读取时丢弃，但不动磁盘
 *    （下次 append 会从它后面接着写）。
 *
 * 为什么不用宿主的 `PluginStorage.atomicWrite`：那是整文件替换，每次追加都要重写全量，
 * 会话一长就是 O(n²)。这里要的是"只写尾部 + fsync"，所以直接用 `rootPath()` 给的目录。
 */
internal class EventStore(root: File) {

    private val dir = File(root, "sessions")
    private val lock = Any()

    fun fileFor(sessionId: String): File = File(dir, "${sanitize(sessionId)}.jsonl")

    /** 追加一行并 fsync。失败时把文件截回原长度，然后原样抛出。 */
    fun append(sessionId: String, line: String) {
        synchronized(lock) {
            fileFor(sessionId).parentFile?.mkdirs()
            RandomAccessFile(fileFor(sessionId), "rw").use { file ->
                val start = file.length()
                try {
                    file.seek(start)
                    file.write((line + "\n").toByteArray(Charsets.UTF_8))
                    // 返回即落盘：调用方据此认为这条事实已经不可丢。
                    file.fd.sync()
                } catch (error: Throwable) {
                    runCatching { file.setLength(start) }
                    throw error
                }
            }
        }
    }

    /**
     * 读回全部**完整**行。
     *
     * 没有以换行结尾的最后一段是崩溃残行，不算完整记录，直接丢弃——
     * 半行比没有更糟，它会在解析时变成一条内容错乱的事实。
     * 解析失败的行由调用方（[SessionLogImpl]）统计，这里只管"行是不是完整的"。
     */
    fun read(sessionId: String): List<String> {
        synchronized(lock) {
            val file = fileFor(sessionId)
            if (!file.isFile) return emptyList()
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (text.isEmpty()) return emptyList()
            val complete = text.substringBeforeLast('\n', missingDelimiterValue = "")
            if (complete.isEmpty()) return emptyList()
            return complete.split('\n').filter { it.isNotBlank() }
        }
    }

    fun delete(sessionId: String): Boolean {
        synchronized(lock) { return fileFor(sessionId).delete() }
    }

    /** 会话 id 可能带 `/`、`..` 之类；映射成安全文件名，且保持可逆性不重要——只求不越界。 */
    private fun sanitize(sessionId: String): String {
        val safe = sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .ifBlank { "_" }
        // 加长度后缀，避免两个不同的 id 清洗后撞到同一个文件。
        return "${safe.take(64)}-${sessionId.hashCode().toUInt().toString(16)}"
    }
}
