package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentService
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageBroker
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 主循环插件的壳。
 *
 * 它自己**不持有任何依赖服务的引用**——`BrainService` 每次调用时现查目录，
 * 因为 `ev-model` 可能比它晚启动，也可能被停用再启用。
 *
 * 一轮怎么走已经不在这个进程里了：`AgentDaemon` 起一个 agent 进程，`DaemonBridge`
 * 把工具、审批和 HTTP 交回宿主。这个文件只做发布与撤销。
 */
class AgentBrainPlugin : Plugin<Unit>, ManifestProvider {

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "主循环",
        version = "1.0.0",
        description = "对话大脑：一轮怎么走、工具怎么并发、危险操作怎么问人。",
        capabilities = listOf(
            PluginCapability(
                id = "agent/service",
                kind = PluginCapability.Kind.SERVICE,
                title = "对话服务",
                description = "send / sendStreaming。",
            ),
        ),
    )

    override val name = "ev-brain"

    companion object {
        /** Also the container file name the host stores: `mods/<id>.jar`. */
        const val ID = "ev.buan.plugin.brain"
    }

    // PluginStorage 由宿主 provide 到本插件自己的子 Context，不属于父链，
    // 所以它不能写进 inject（inject 只声明继承来的服务），apply 里 require 即可。
    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        // Storage holds the extracted agent binary; the container ships it in
        // `bin/ev-agentd` and it is unpacked on first use.
        val storage = directory.lookup(PluginStorageBroker.Key)?.storageFor(ID)
        val brain = BrainService(directory, storage)
        directory.publish(AgentService.Key, brain)
        // 管理页的入口：同一个对象，另一条契约。页面只读状态、改参数，不碰 IPC。
        directory.publish(org.cordis.api.AgentRuntime.Key, brain)

        effect.collect {
            if (directory.lookup(AgentService.Key) === brain) {
                directory.retract(AgentService.Key)
            }
            if (directory.lookup(org.cordis.api.AgentRuntime.Key) === brain) {
                directory.retract(org.cordis.api.AgentRuntime.Key)
            }
        }
    }
}
