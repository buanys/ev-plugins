package ev.buan.plugin.brain

import ev.buan.plugins.agent.api.AgentService
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies

/**
 * 主循环插件的壳。
 *
 * 它自己**不持有任何依赖服务的引用**——`BrainService` 每次调用时现查目录，
 * 因为 `ev-model` 可能比它晚启动，也可能被停用再启用。
 *
 * 有逻辑的部分：`AgentLoop`（一轮怎么走）、`ToolPipeline`（一批工具怎么排）、
 * `ApprovalChain`（危险工具怎么问）。这个文件只做发布与撤销。
 */
class AgentBrainPlugin : Plugin<Unit>, ManifestProvider {

    override val manifest: PluginManifest = PluginManifest(
        id = "ev.buan.plugin.brain",
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

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val brain = BrainService(directory)
        directory.publish(AgentService.Key, brain)

        effect.collect {
            if (directory.lookup(AgentService.Key) === brain) {
                directory.retract(AgentService.Key)
            }
        }
    }
}
