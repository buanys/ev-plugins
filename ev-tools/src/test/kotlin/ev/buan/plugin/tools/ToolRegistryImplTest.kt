package ev.buan.plugin.tools

import ev.buan.plugins.agent.api.Tool
import ev.buan.plugins.agent.api.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 总线本身的规矩：**同名工具只允许一个所有者**。
 *
 * 这条不是洁癖。模型看到的工具名必须唯一解析到一个实现——多一个同名工具，
 * "模型点了这个却执行了那个"就是发布缺陷，而且现场很难复现。
 */
class ToolRegistryImplTest {

    private fun tool(name: String): Tool = object : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "测试工具 $name",
            parametersJson = """{"type":"object","properties":{}}""",
        )

        override suspend fun invoke(argumentsJson: String): String = name
    }

    @Test
    fun `注册后能按名字查到`() {
        val registry = ToolRegistryImpl()
        val read = tool("read")
        registry.register("ev-owner", listOf(read))
        assertSame(read, registry.lookup("read"))
    }

    @Test
    fun `specs 按注册顺序排列`() {
        val registry = ToolRegistryImpl()
        registry.register("a", listOf(tool("a1"), tool("a2")))
        registry.register("b", listOf(tool("b1")))
        assertEquals(listOf("a1", "a2", "b1"), registry.specs().map { it.name })
    }

    @Test
    fun `同一所有者再注册是替换而不是追加`() {
        val registry = ToolRegistryImpl()
        registry.register("owner", listOf(tool("old")))
        registry.register("owner", listOf(tool("new")))

        assertEquals(listOf("new"), registry.specs().map { it.name })
        assertNull(registry.lookup("old"))
    }

    @Test
    fun `撤销后查不到，其它所有者不受影响`() {
        val registry = ToolRegistryImpl()
        registry.register("a", listOf(tool("a1")))
        registry.register("b", listOf(tool("b1")))

        registry.unregister("a")

        assertNull(registry.lookup("a1"))
        assertSame("b1", registry.lookup("b1")?.spec?.name)
    }

    @Test
    fun `撤销一个没注册过的所有者是空操作`() {
        val registry = ToolRegistryImpl()
        registry.register("a", listOf(tool("a1")))
        registry.unregister("不存在")
        assertEquals(1, registry.specs().size)
    }

    @Test
    fun `跨所有者同名直接抛，不静默覆盖`() {
        val registry = ToolRegistryImpl()
        registry.register("a", listOf(tool("shared")))

        val error = runCatching { registry.register("b", listOf(tool("shared"))) }.exceptionOrNull()

        assertTrue("应当是 IllegalArgumentException，实际 $error", error is IllegalArgumentException)
        // 抛完之后原来那条还在，不能被写坏。
        assertEquals(listOf("shared"), registry.specs().map { it.name })
    }

    @Test
    fun `同一个所有者内部重名也抛`() {
        val registry = ToolRegistryImpl()
        val error = runCatching {
            registry.register("a", listOf(tool("dup"), tool("dup")))
        }.exceptionOrNull()

        assertTrue("应当是 IllegalArgumentException，实际 $error", error is IllegalArgumentException)
        assertTrue(registry.specs().isEmpty())
    }

    @Test
    fun `注册空列表等同于宣告这个所有者没有工具`() {
        val registry = ToolRegistryImpl()
        registry.register("a", listOf(tool("a1")))
        registry.register("a", emptyList())

        assertTrue(registry.specs().isEmpty())
    }

    @Test
    fun `未知名字返回 null 而不是抛`() {
        assertNull(ToolRegistryImpl().lookup("nope"))
    }
}
