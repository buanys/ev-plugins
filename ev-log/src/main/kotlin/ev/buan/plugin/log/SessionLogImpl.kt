package ev.buan.plugin.log

import ev.buan.plugins.agent.api.AbandonReason
import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.SessionEvent
import ev.buan.plugins.agent.api.SessionLog

/**
 * [SessionLog] 的实现：内存里持有当前会话的事件，磁盘上一条一行。
 *
 * 三个行为约定：
 *
 * 1. **append 返回即落盘**（[EventStore] 里 fsync）。调用方据此认为事实已经不可丢。
 * 2. **读取时补洞**：一个会话第一次被打开时，检查"请求过但没有结果的工具调用"，
 *    为每一个追加一条 [SessionEvent.ToolAbandoned]。补洞是**追加**，不改写历史。
 * 3. **坏行如实上报**：解析不了的行会被跳过并计数，日志里看得到，不静默当成"历史就这么长"。
 *
 * 为什么补洞放在读路径而不是启动时统一扫：会话是按需打开的，没被打开的会话不该
 * 产生写操作；而一旦有人要读，那条洞就必须在返回之前补上，否则模型会看到
 * "assistant 请求了工具、却没有对应结果"的残缺历史——多数 provider 会直接报错。
 */
internal class SessionLogImpl(private val store: EventStore) : SessionLog {

    private val lock = Any()
    private val cache = mutableMapOf<String, MutableList<SessionEvent>>()

    override suspend fun append(sessionId: String, event: SessionEvent) {
        synchronized(lock) {
            val events = ensureLoaded(sessionId)
            store.append(sessionId, EventCodec.encode(event))
            events += event
        }
    }

    override suspend fun messages(sessionId: String): List<AgentMessage> = synchronized(lock) {
        HistoryProjection.messages(ensureLoaded(sessionId))
    }

    override suspend fun events(sessionId: String): List<SessionEvent> = synchronized(lock) {
        ensureLoaded(sessionId).toList()
    }

    override suspend fun clear(sessionId: String) {
        synchronized(lock) {
            store.delete(sessionId)
            cache[sessionId] = mutableListOf()
        }
    }

    /** 载入并补洞。补洞只在第一次打开时做。 */
    private fun ensureLoaded(sessionId: String): MutableList<SessionEvent> {
        cache[sessionId]?.let { return it }

        val loaded = mutableListOf<SessionEvent>()
        var unreadable = 0
        for (line in store.read(sessionId)) {
            val event = EventCodec.decode(line)
            if (event == null) unreadable++ else loaded += event
        }

        // 补洞：请求过但永远没有结果的调用。原因统一记 OUTCOME_UNKNOWN——
        // 我们没有"还没开始跑"的证据，而把它当成"没跑过"会让重试踩到已经发生的副作用。
        val orphans = HistoryProjection.unansweredCalls(loaded)
        for (call in orphans) {
            val repair = SessionEvent.ToolAbandoned(
                callId = call.id,
                toolName = call.name,
                reason = AbandonReason.OUTCOME_UNKNOWN,
            )
            store.append(sessionId, EventCodec.encode(repair))
            loaded += repair
        }

        if (unreadable > 0 || orphans.isNotEmpty()) {
            android.util.Log.w(
                "EV",
                "ev-log: $sessionId 载入 ${loaded.size} 条事件；" +
                    "跳过 $unreadable 条坏行；补 ${orphans.size} 条工具结果洞",
            )
        }

        cache[sessionId] = loaded
        return loaded
    }
}
