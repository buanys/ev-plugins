package ev.buan.plugin.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ev.buan.plugins.agent.api.ModelDescriptor
import ev.buan.plugins.agent.api.ModelProvider
import ev.buan.plugins.agent.api.ModelProviderService
import ev.buan.ui.EvButton
import ev.buan.ui.EvButtonKind
import ev.buan.ui.EvColors
import ev.buan.ui.EvRow
import ev.buan.ui.EvSection
import ev.buan.ui.EvText
import com.itos.coui.text.CouiTextKind
import ev.buan.ui.EvTone
import kotlinx.coroutines.launch

/**
 * 设置页里的模型端点编辑。
 *
 * 这块是插件自己画的，宿主只提供位置（`UiSlot.SettingsSections`）和主题：
 * 表面色、字号、语气都取 `ev.buan.ui` 的 token，所以它和宿主自带的几段长得是一家人。
 *
 * 交互上只有三件事：选一个端点 → 填地址 / key / 模型名 → 存。存完立刻重读服务，
 * 不维护第二份状态：`providers.json` 是唯一的真相源。
 */
@Composable
internal fun ModelSettings(service: ModelProviderService) {
    var providers by remember { mutableStateOf(service.providers()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        providers = service.providers()
    }

    EvSection(title = "模型", footnote = "由模型插件提供") {
        providers.forEach { provider ->
            EvRow(
                title = provider.name,
                summary = "${provider.baseUrl.ifBlank { "没有地址" }} · ${provider.selectedModelId ?: provider.models.firstOrNull()?.id ?: "没选模型"}",
                onClick = {
                    val opening = editing != provider.id
                    editing = if (opening) provider.id else null
                    notice = null
                    if (opening) {
                        baseUrl = provider.baseUrl
                        apiKey = provider.apiKey
                        modelId = provider.selectedModelId ?: provider.models.firstOrNull()?.id.orEmpty()
                    }
                },
                trailing = {
                    EvText(
                        if (usable(provider)) "可用" else "未配置",
                        kind = CouiTextKind.PreferenceSummary,
                        tone = if (usable(provider)) EvTone.Accent else EvTone.Warning,
                    )
                },
            )
            if (editing == provider.id) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Field("接口地址", baseUrl, { baseUrl = it }, "https://api.deepseek.com/v1")
                    Field("API Key", apiKey, { apiKey = it }, "sk-…", secret = true)
                    Field("模型名", modelId, { modelId = it }, "deepseek-chat")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EvButton("保存", onClick = {
                            val saved = withDraft(provider, baseUrl, apiKey, modelId)
                            service.saveProvider(saved)
                            reload()
                            notice = "已保存 ${provider.name}"
                        })
                        EvButton(
                            "测试连接",
                            kind = EvButtonKind.Secondary,
                            onClick = {
                                scope.launch {
                                    notice = "正在测试 ${provider.name}…"
                                    val result = runCatching { service.testConnection(provider.id) }
                                    notice = result.fold(
                                        onSuccess = { if (it.success) "✓ ${it.message}" else "✗ ${it.message}" },
                                        onFailure = { "✗ ${it.message ?: "测试失败"}" },
                                    )
                                }
                            },
                        )
                        EvButton(
                            if (provider.enabled) "停用" else "启用",
                            kind = EvButtonKind.Secondary,
                            enabled = provider.enabled || provider.apiKey.isNotBlank() || apiKey.isNotBlank(),
                            onClick = {
                                val toggled = withDraft(provider, baseUrl, apiKey, modelId)
                                    .copy(enabled = !provider.enabled)
                                service.saveProvider(toggled)
                                reload()
                                notice = if (toggled.enabled) "已启用 ${provider.name}" else "已停用 ${provider.name}"
                            },
                        )
                    }
                }
            }
        }
        notice?.let { text ->
            EvText(
                text,
                kind = CouiTextKind.PreferenceSummary,
                tone = if (text.startsWith("✗")) EvTone.Danger else EvTone.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 把编辑框里的三行字变回一个 [ModelProvider]，其余字段原样保留。 */
private fun withDraft(
    provider: ModelProvider,
    baseUrl: String,
    apiKey: String,
    modelId: String,
): ModelProvider {
    val id = modelId.trim()
    val models = if (id.isEmpty() || provider.models.any { it.id == id }) {
        provider.models
    } else {
        provider.models + ModelDescriptor(id = id)
    }
    return provider.copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        enabled = provider.enabled || apiKey.isNotBlank(),
        selectedModelId = id.takeIf { it.isNotEmpty() } ?: provider.selectedModelId,
        models = models,
    )
}

private fun usable(provider: ModelProvider): Boolean =
    provider.enabled && provider.apiKey.isNotBlank() && provider.models.isNotEmpty()

/**
 * 一行输入：标题在上，输入区在下。
 *
 * 用 [BasicTextField] 而不是 Material 的 TextField：后者带一整套自己的形状、间距和
 * 浮动标签，放进这套 token 里就是要它"看起来像另一家的控件"。
 */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    val colors = EvColors
    val style = TextStyle(color = colors.textPrimary, fontSize = 14.sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EvText(label, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface2, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    EvText(placeholder, kind = CouiTextKind.PreferenceSummary, tone = EvTone.Muted)
                }
                inner()
            },
        )
    }
}
