package ev.buan.plugin.log

import ev.buan.plugins.agent.api.SessionLog
import java.io.File
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.PluginStorage
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 事件流插件的壳：拿存储、建日志、发布、撤销。
 *
 * 有逻辑的部分都在 [SessionLogImpl] / [HistoryProjection] / [EventStore] 里，
 * 这个文件只做接线——壳越薄，能在 JVM 上测的就越多。
 */
class SessionLogPlugin : Plugin<Unit>, ManifestProvider {

    override val manifest: PluginManifest = PluginManifest(
        id = "ev.buan.plugin.log",
        name = "事件流",
        version = "1.0.0",
        description = "会话事件的唯一真相源：append-only 落盘、可回放、崩溃后补洞。",
        capabilities = listOf(
            PluginCapability(
                id = "agent/session-log",
                kind = PluginCapability.Kind.SERVICE,
                title = "会话事件流",
                description = "append / messages / events / clear。",
            ),
        ),
    )

    override val name = "ev-log"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key, PluginStorage.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val storage = ctx.require(PluginStorage.Key)

        // 没有可写目录就没有"不可丢"这回事——宁可起不来，也别假装在记日志。
        val root = storage.rootPath()
        require(root.isNotBlank()) { "ev-log: PluginStorage 没有给出可用目录，事件流无法落盘" }

        val log = SessionLogImpl(EventStore(File(root)))
        directory.publish(SessionLog.Key, log)

        // 热重载会造出第二个实例，撤销前必须确认撤的是自己那一个。
        effect.collect {
            if (directory.lookup(SessionLog.Key) === log) {
                directory.retract(SessionLog.Key)
            }
        }
    }
}
