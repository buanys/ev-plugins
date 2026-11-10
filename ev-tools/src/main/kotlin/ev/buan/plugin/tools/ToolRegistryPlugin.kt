package ev.buan.plugin.tools

import ev.buan.plugins.agent.api.ToolRegistry
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
 * 工具总线插件的壳。
 *
 * 壳薄是有意的：这个文件只做四件事——声明自己、拿目录、发布总线、撤销。
 * 真正有逻辑的部分在 [ToolRegistryImpl]，那边是纯 Kotlin，单测跑在 JVM 上，
 * 不用起一个宿主、一个 Context、一个 ServiceDirectory（见 `_template` 的注释）。
 *
 * **它不注册任何工具。** 能力插件在 `apply` 里拿 `ToolRegistry`，注册自己的工具，
 * 停用时 `unregister(自己的 id)`。总线只负责让它们互不踩脚。
 */
class ToolRegistryPlugin : Plugin<Unit>, ManifestProvider {

    override val manifest: PluginManifest = PluginManifest(
        id = "ev.buan.plugin.tools",
        name = "工具总线",
        version = "1.0.0",
        description = "全应用唯一的工具注册表。它自己不提供工具，只让能力插件把工具挂上来。",
        capabilities = listOf(
            PluginCapability(
                id = "agent/tool-registry",
                kind = PluginCapability.Kind.SERVICE,
                title = "工具注册表",
                description = "specs / lookup / register / unregister。",
            ),
        ),
    )

    override val name = "ev-tools"

    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val registry = ToolRegistryImpl()
        directory.publish(ToolRegistry.Key, registry)

        // 热重载会造出第二个实例，所以撤销前必须确认撤的是自己那一个。
        effect.collect {
            if (directory.lookup(ToolRegistry.Key) === registry) {
                directory.retract(ToolRegistry.Key)
            }
        }
    }
}
