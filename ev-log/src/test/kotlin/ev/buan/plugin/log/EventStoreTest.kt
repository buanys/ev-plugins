package ev.buan.plugin.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘的守卫。这一层的每个失败模式都会让"日志是真相源"这句话变成假的：
 * - 尾部残行被当完整记录 → 读出一条错乱的事
 * - 两个会话 id 清洗后撞同一个文件 → 历史互相覆盖
 * - 删除不生效 → 清空对话变成假的
 */
class EventStoreTest {

    private fun tempRoot(): File = File.createTempFile("ev-log", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    @Test
    fun `追加后能原样读回`() {
        val store = EventStore(tempRoot())
        store.append("s1", "第一条")
        store.append("s1", "第二条")
        assertEquals(listOf("第一条", "第二条"), store.read("s1"))
    }

    @Test
    fun `没有以换行结尾的残行不算完整记录`() {
        val root = tempRoot()
        val store = EventStore(root)
        store.append("s1", "完整")
        // 模拟崩溃：直接往文件尾部写半行，不带换行。
        store.fileFor("s1").appendBytes("半行".toByteArray(Charsets.UTF_8))

        assertEquals(listOf("完整"), store.read("s1"))
    }

    @Test
    fun `不同会话写到不同文件`() {
        val store = EventStore(tempRoot())
        store.append("a", "A")
        store.append("b", "B")
        assertEquals(listOf("A"), store.read("a"))
        assertEquals(listOf("B"), store.read("b"))
    }

    @Test
    fun `清洗后会撞名的两个 id 仍然分开`() {
        val store = EventStore(tempRoot())
        // 两个 id 清洗后字符串部分相同，只有哈希后缀不同。
        store.append("a/b", "斜杠")
        store.append("a_b", "下划线")
        assertEquals(listOf("斜杠"), store.read("a/b"))
        assertEquals(listOf("下划线"), store.read("a_b"))
    }

    @Test
    fun `读一个不存在的会话返回空`() {
        assertTrue(EventStore(tempRoot()).read("没有这个会话").isEmpty())
    }

    @Test
    fun `删除后读不到`() {
        val store = EventStore(tempRoot())
        store.append("s1", "内容")
        assertTrue(store.delete("s1"))
        assertTrue(store.read("s1").isEmpty())
        assertFalse("再删一次应当返回 false", store.delete("s1"))
    }

    @Test
    fun `空字符串不会被当成一条记录`() {
        val store = EventStore(tempRoot())
        store.append("s1", "")
        assertEquals(emptyList<String>(), store.read("s1"))
    }
}
