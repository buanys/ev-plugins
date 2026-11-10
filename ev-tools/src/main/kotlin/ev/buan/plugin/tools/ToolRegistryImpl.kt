package ev.buan.plugin.tools

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolRegistry
import ev.buan.plugins.agent.api.ToolSpec

/**
 * 工具总线：全应用唯一的一份工具注册表。
 *
 * 它自己**不提供任何工具**。实验版把"总线"和"一堆文件工具"塞在同一个插件里，
 * 结果是任何一个插件想注册工具都得连带装上一整套文件读写——这既不是它的职责，
 * 也让"谁给了模型这个能力"这件事变得说不清。正式版里工具属于各自的能力插件，
 * 这里只负责收发。
 *
 * 三条规矩：
 *
 * 1. **同名工具只允许一个所有者**。模型看到的工具名必须能唯一解析到一个实现，
 *    否则"点了这个按钮却执行了另一个"是发布缺陷，不是运行时该容忍的情况。
 * 2. **register 是替换，不是追加**。插件被重新激活时再注册一遍是正常路径，
 *    不该出现两份。
 * 3. **顺序稳定**。按所有者首次注册的顺序排列，同一所有者内部按声明顺序——
 *    模型看到的工具表每次一样，缓存与回放才有意义。
 */
internal class ToolRegistryImpl : ToolRegistry {

    private val lock = Any()

    /** owner → 它注册的工具。LinkedHashMap 保住注册顺序。 */
    private val owners = LinkedHashMap<String, List<Tool>>()

    override fun specs(): List<ToolSpec> = synchronized(lock) {
        owners.values.flatMap { tools -> tools.map { it.spec } }
    }

    override fun lookup(name: String): Tool? = synchronized(lock) {
        owners.values.firstNotNullOfOrNull { tools -> tools.firstOrNull { it.spec.name == name } }
    }

    override fun register(owner: String, tools: List<Tool>) {
        val names = tools.map { it.spec.name }
        require(names.size == names.toSet().size) {
            "同一个插件注册了重名工具：${names.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
        synchronized(lock) {
            val elsewhere = owners
                .filterKeys { it != owner }
                .values
                .flatMap { registered -> registered.map { it.spec.name } }
                .toSet()
            val clash = names.filter { it in elsewhere }
            require(clash.isEmpty()) { "工具名已被其它插件占用：$clash" }
            owners[owner] = tools.toList()
        }
    }

    override fun unregister(owner: String) {
        synchronized(lock) { owners.remove(owner) }
    }
}
