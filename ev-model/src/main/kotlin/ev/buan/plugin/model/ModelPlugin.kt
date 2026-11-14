package ev.buan.plugin.model

import ev.buan.plugins.agent.api.AgentMessage
import ev.buan.plugins.agent.api.AgentReply
import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelEndpointMode
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.plugins.agent.api.ModelRef
import ev.buan.plugins.agent.api.ModelService
import ev.buan.plugins.agent.api.ModelTestResult
import ev.buan.plugins.agent.api.ToolSpec
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cordis.Context
import org.cordis.Dependencies
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.api.ManifestProvider
import org.cordis.api.PluginCapability
import org.cordis.api.PluginManifest
import org.cordis.api.PluginStorage
import org.cordis.api.PluginStorageBroker
import org.cordis.api.UiSlot
import org.cordis.api.UiSlotRegistry
import org.cordis.api.ServiceDirectory
import org.cordis.dependencies
import org.json.JSONArray
import org.json.JSONObject

/**
 * Endpoint configuration and the models it serves.
 *
 * Two services, one owner: [ModelProviderService] is the settings surface
 * (what the user configured), [ModelService] is what a consumer sees (which
 * models exist right now). The agent process does not call [ModelService] - it
 * runs rig itself and receives the endpoint per turn - but the contract stays
 * whole, so a caller that only knows [ModelService] still works.
 */
class ModelPlugin : Plugin<Unit>, ManifestProvider {

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "模型",
        version = "1.0.0",
        description = "OpenAI 兼容端点与模型清单：配置、选择、连通性检查。",
        capabilities = listOf(
            PluginCapability(
                id = "agent/model-providers",
                kind = PluginCapability.Kind.SERVICE,
                title = "模型配置",
                description = "providers / saveProvider / fetchRemoteModels / testConnection。",
            ),
            PluginCapability(
                id = "agent/model",
                kind = PluginCapability.Kind.SERVICE,
                title = "模型服务",
                description = "models / complete / completeStreaming。",
            ),
        ),
    )

    override val name = "ev-model"

    // PluginStorage 由宿主 provide 到本插件自己的子 Context，不属于父链，
    // 所以它不能写进 inject（inject 只声明继承来的服务），apply 里 require 即可。
    override val inject: Dependencies = dependencies(ServiceDirectory.Key)

    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        val directory = ctx.require(ServiceDirectory.Key)
        val area = directory.lookup(PluginStorageBroker.Key)?.storageFor(ID)
            ?: error("插件存储不可用：先确认容器里声明了 PLUGIN_STORAGE")
        val store = ProviderStore(File(area.rootPath(), "providers.json"))
        val providers = ModelProviderImpl(store)
        val models = ModelServiceImpl(store)
        directory.publish(ModelProviderService.Key, providers)
        directory.publish(ModelService.Key, models)
        // 设置页里的那一小块：端点列表 + 编辑（地址 / key / 模型名）。
        // 这块界面由插件自己画，宿主只给位置和主题。
        directory.lookup(UiSlotRegistry.Key)?.let { slots ->
            slots.contribute(manifest.id, UiSlot.SettingsSections, priority = 0) {
                ModelSettings(providers)
            }
        }

        effect.collect {
            directory.lookup(UiSlotRegistry.Key)?.revoke(manifest.id)
            if (directory.lookup(ModelProviderService.Key) === providers) {
                directory.retract(ModelProviderService.Key)
            }
            if (directory.lookup(ModelService.Key) === models) directory.retract(ModelService.Key)
        }
    }

    companion object {
        const val ID = "ev.buan.plugin.model"
    }
}

/**
 * Provider configuration, one small JSON file.
 *
 * Built-in entries are a catalog, not stored state: they are merged in on every
 * read, so a shipped default that changes reaches an install that never touched
 * it, while the user's key and model pick survive.
 */
internal class ProviderStore(private val file: File) {

    private val lock = Any()

    fun all(): List<ModelProvider> = synchronized(lock) {
        val stored = read()
        val byId = stored.associateBy { it.id }
        (catalog().map { builtIn -> byId[builtIn.id]?.let { merge(builtIn, it) } ?: builtIn } +
            stored.filter { provider -> catalog().none { it.id == provider.id } })
    }

    fun save(provider: ModelProvider) = synchronized(lock) {
        val stored = read().filterNot { it.id == provider.id } + provider
        write(stored)
    }

    fun remove(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    fun reset(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    private fun merge(builtIn: ModelProvider, stored: ModelProvider): ModelProvider = stored.copy(
        name = stored.name.ifBlank { builtIn.name },
        baseUrl = stored.baseUrl.ifBlank { builtIn.baseUrl },
        builtIn = true,
        models = stored.models.ifEmpty { builtIn.models },
    )

    private fun read(): List<ModelProvider> {
        if (!file.isFile) return emptyList()
        return runCatching { decode(JSONObject(file.readText())) }.getOrDefault(emptyList())
    }

    private fun write(providers: List<ModelProvider>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("providers", JSONArray().apply {
            providers.forEach { put(encode(it)) }
        }).toString(2))
    }

    private fun encode(provider: ModelProvider): JSONObject = JSONObject()
        .put("id", provider.id)
        .put("name", provider.name)
        .put("baseUrl", provider.baseUrl)
        .put("apiKey", provider.apiKey)
        .put("enabled", provider.enabled)
        .put("builtIn", provider.builtIn)
        .put("selectedModelId", provider.selectedModelId ?: JSONObject.NULL)
        .put("headers", JSONObject(provider.headers as Map<*, *>))
        .put("models", JSONArray().apply {
            provider.models.forEach { model ->
                put(JSONObject().put("id", model.id).put("displayName", model.displayName))
            }
        })

    private fun decode(root: JSONObject): List<ModelProvider> {
        val array = root.optJSONArray("providers") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ModelProvider(
                id = id,
                name = item.optString("name", id),
                baseUrl = item.optString("baseUrl"),
                apiKey = item.optString("apiKey"),
                endpointMode = ModelEndpointMode.CHAT_COMPLETIONS,
                headers = item.optJSONObject("headers")?.let { headers ->
                    headers.keys().asSequence().associateWith { headers.optString(it) }
                } ?: emptyMap(),
                enabled = item.optBoolean("enabled", true),
                builtIn = false,
                models = item.optJSONArray("models")?.let { models ->
                    (0 until models.length()).mapNotNull { position ->
                        val model = models.optJSONObject(position) ?: return@mapNotNull null
                        val modelId = model.optString("id").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        ModelDescriptor(
                            id = modelId,
                            displayName = model.optString("displayName", modelId),
                        )
                    }
                } ?: emptyList(),
                selectedModelId = item.optString("selectedModelId")
                    .takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    private fun catalog(): List<ModelProvider> = listOf(
        ModelProvider(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            enabled = false,
            builtIn = true,
            models = listOf(
                ModelDescriptor("deepseek-chat", "DeepSeek Chat", "128K"),
                ModelDescriptor("deepseek-reasoner", "DeepSeek Reasoner", "128K"),
            ),
        ),
        ModelProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            enabled = false,
            builtIn = true,
            models = listOf(ModelDescriptor("gpt-4o-mini", "GPT-4o mini", "128K")),
        ),
        ModelProvider(
            id = "custom",
            name = "自定义端点",
            baseUrl = "",
            enabled = false,
            builtIn = true,
            models = emptyList(),
        ),
    )

    companion object {
        /** A built-in entry with no key can never answer, so it counts as off. */
        fun usable(provider: ModelProvider): Boolean =
            provider.enabled && provider.apiKey.isNotBlank() && provider.models.isNotEmpty()
    }
}

internal class ModelProviderImpl(private val store: ProviderStore) : ModelProviderService {

    override fun providers(): List<ModelProvider> = store.all()

    override fun saveProvider(provider: ModelProvider) = store.save(provider)

    override fun deleteProvider(id: String) = store.remove(id)

    override fun resetProvider(id: String) = store.reset(id)

    override suspend fun fetchRemoteModels(providerId: String): List<ModelDescriptor> {
        val provider = store.all().firstOrNull { it.id == providerId }
            ?: error("没有这个端点：$providerId")
        val body = get(provider, "/models")
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val model = data.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ModelDescriptor(id = id)
        }
    }

    override suspend fun testConnection(providerId: String): ModelTestResult {
        val provider = store.all().firstOrNull { it.id == providerId }
            ?: return ModelTestResult(false, "没有这个端点：$providerId")
        return runCatching { get(provider, "/models") }
            .map { ModelTestResult(true, "连上了，模型列表可以读到") }
            .getOrElse { error -> ModelTestResult(false, error.message ?: "连接失败") }
    }

    private suspend fun get(provider: ModelProvider, path: String): String =
        withContext(Dispatchers.IO) {
            val connection = URL(provider.baseUrl.trimEnd('/') + path).openConnection()
                as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            provider.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (provider.apiKey.isNotBlank() && !provider.headers.containsKey("Authorization")) {
                connection.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            connection.disconnect()
            if (status !in 200..299) error("HTTP $status：${text.take(200)}")
            text
        }
}

/**
 * The models currently on offer, and one-shot completions for callers that do
 * not run their own loop.
 *
 * The agent process speaks to the endpoint directly (given the configuration
 * per turn), so this path is for everything else: a UI probing a model, a
 * plugin that wants one completion without a loop.
 */
internal class ModelServiceImpl(private val store: ProviderStore) : ModelService {

    override val models: List<ModelRef>
        get() = store.all()
            .filter { ProviderStore.usable(it) }
            .flatMap { provider ->
                val picked = provider.selectedModelId
                provider.models
                    .filter { picked == null || it.id == picked }
                    .map { ModelRef(provider.id, it.id) }
            }

    override suspend fun complete(
        model: ModelRef,
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
    ): AgentReply = withContext(Dispatchers.IO) {
        val provider = store.all().firstOrNull { it.id == model.provider }
            ?: error("没有这个端点：${model.provider}")
        val payload = JSONObject()
            .put("model", model.model)
            .put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", message.role.name.lowercase())
                            .put("content", message.content),
                    )
                }
            })
        val connection = URL(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("Content-Type", "application/json")
        provider.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (provider.apiKey.isNotBlank() && !provider.headers.containsKey("Authorization")) {
            connection.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }
        val body = payload.toString().toByteArray()
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }

        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        if (status !in 200..299) error("HTTP $status：${text.take(300)}")

        val content = JSONObject(text)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
        AgentReply(message = AgentMessage(ev.buan.plugins.agent.api.AgentRole.ASSISTANT, content), model = model)
    }
}
