# AGENTS.md — 写 EV 插件之前先读这个

宿主在 `/workspace/EV`（那边**不含任何插件**），两边用 `includeBuild` 连起来。
总体说明读 `README.md`，宿主侧的设计与能力面读宿主仓库的 `AGENTS.md` 和 `docs/sdk/`。

## 1. 模块

| 模块 | 插件 id | 用途 |
|---|---|---|
| `ev-brain` | `ev.buan.plugin.brain` | agent 主循环，托管 `libev_agentd.so` 子进程 |
| `ev-model` | `ev.buan.plugin.model` | 模型端点配置，并把编辑表单做成独立 `AgentPage` |
| `ev-tools` | `ev.buan.plugin.tools` | 工具总线 |
| `ev-log` | `ev.buan.plugin.log` | 会话事件流 |

## 2. 构建与部署

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ANDROID_HOME=/opt/android-sdk \
/opt/gradle-9.7.1/bin/gradle :ev-model:bundlePlugin --console=plain
# 产物：ev-model/build/dist/模型-core.jar
```

部署**不是**拷到 `/sdcard/EV/mods`。插件住在应用私有目录，而 `/data/data/ev.buan`
在 Eta 的 mount namespace 里被遮住，必须 `nsenter` 进 init 的 namespace：
步骤见 `README.md` 的「装到设备」，三件事缺一不可：清 `code_cache/cordis-plugins/<id>`、
清 `files/crash/crash.pending`、重启进程。

## 3. 三种会立刻炸的错

1. **宿主的 `:ui-kit` / `:host-api` 改了公开签名，插件必须全部重建重装。**
   插件与宿主共享 `ev.buan.ui` 类（`HostPackages.shared`），`EvRow` 加一个参数就是新签名，
   旧 jar 一调就 `NoSuchMethodError`，整机进安全模式。实测两次：删 `EvTextKind`、`EvRow` 加 `icon`。
2. **引用 COUI 类型时，`com.itos.coui` 必须在 `HostPackages.shared` 里**（宿主侧）。
   插件的 `IsolatedPluginClassLoader` 只转发共享前缀，漏了就是
   `NoClassDefFoundError: Failed resolution of Lcom/itos/coui/...`。
3. **插件类要提供整页，必须自己 `implements AgentPage`。**
   另外写一个类、只把方法转发过去不行：宿主 `HostRouting.pageFor` 用的是
   `instance as? AgentPage`，判定的是**插件实例本身**，返回 null 时入口点了会原地弹回上一页。

## 4. UI 从哪来

插件的界面跑在宿主进程里，**直接用 `ev.buan.ui` 的组件和 `CouiTextKind`**，
不要自带一套 Material、也不要自带一份 ui-kit（`RuntimeClasspath` 已排除）。

- 一行、一组、一段文字：`EvRow` / `EvSection` / `EvText`
- 整页：实现 `AgentPage`，页里用 `EvPage(title, onBack)`
- 设置页里的入口行：贡献到 `UiSlot.SettingsSections`，点击调
  `UiSlotRegistry.openPage("<pageId>")` 请求宿主导航（宿主认识 `model`）
- 状态用形状：开关 `Switch`、单选 `RadioButton`、连接态色点。不要写"已启用/未启用"

## 5. 汇报口径

做了什么 / 还剩什么 / 卡在哪，附原始输出。没实测就写"未实测"。
推翻自己上一版时把**为什么**写进注释——"我试过 X，它坏在 Y"比"这里做了 X"值钱。
