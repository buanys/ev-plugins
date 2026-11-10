# ev-plugins

EV 的插件都住在这里。宿主在 `/workspace/EV`，**那边已经不含任何插件**——
两边用 `includeBuild` 连起来，插件按坐标引用宿主的契约模块。

## 目录

```
ev-tools/        工具总线（骨架第一件）
probe-remote/    受限档子进程的验证夹具，不是产品
```

一个插件一个模块，模块名就是它的英文基础名（`ev-` 前缀）。

## 产物命名：中文给人，英文给机器

```
工具总线-base.jar
工具总线-base.json
```

| 位置 | 形式 | 为什么 |
|---|---|---|
| 文件名 | `<中文名>-<分类>.jar` | 在 `/sdcard/EV/mods` 里一眼认出是什么 |
| 分类 | `base` / `feature` / `integration` / `resource` | 与宿主仓库里 `plugins/<类>/<名>` 那一层对应 |
| 插件 id | `ev.buan.plugin.tools` 之类，全英文 | 加载、比对、日志都靠它，不能有中文 |
| 入口类 | 全英文全限定名 | 反射加载 |

**jar 与 sidecar 必须同名同前缀**：宿主按「jar 去掉扩展名 + `.json`」找 sidecar，
两个名字对不上，插件会被当成没有元数据（`PluginMetadataReader.readSidecarMetadata`）。

改名时注意：`bundlePlugin` 的自检要求 `dist/` 里恰好一个 jar + 一个 sidecar，
所以打包任务会先清空 `dist`——否则旧名字的文件会留在那儿，改名反而构建失败。

## 契约从哪来

```kotlin
// settings.gradle.kts
includeBuild("/workspace/EV")

// 插件模块
compileOnly("io.github.buanys:host-api:0.0.1-SNAPSHOT")
compileOnly("io.github.buanys:loader:0.0.1-SNAPSHOT")
```

坐标被 `includeBuild` 替换成宿主源码模块：不发布、不下载 aar，
但编译期拿到的是**真实契约**——宿主改了接口，插件下次编译立刻报错。

版本钉在根 `build.gradle.kts`：AGP 9 自带 Kotlin，用 classpath 上那个 KGP；
classpath 上没有它就回退到内置的 2.2.0，而宿主用 2.4.20 编译，会报元数据不兼容。

## 构建

```sh
gradle :ev-tools:testDebugUnitTest     # 单测
gradle :ev-tools:bundlePlugin          # 产物在 ev-tools/build/dist/
```

## 装到设备

产物（jar + json，两个一起）放进 `/sdcard/EV/mods/`，然后重启 EV。
未签名插件需要人工批准——这是宿主的信任门禁，不是缺陷。

## 写新插件时要遵守的

- `id` 用 `ev.buan.plugin.*`，不要占用 `ev.host` / `ev.core` / `org.cordis`
- sidecar 里声明 `hostApi` 范围（如 `>=1.0.0 <2.0.0`），不要只写整数 `apiVersion`
- 壳薄、逻辑纯：插件类只做「声明自己 / 拿目录 / 发布 / 撤销」，
  真正有逻辑的部分放普通 Kotlin 类，好在 JVM 上测
- 声明的权限要和实际用到的服务一一对应（见宿主仓库 `docs/sdk/host-services.md`）

## 与宿主的分工

宿主负责所有需要**整包级 Android 权限**的事（日历、通讯录、短信、媒体库、定位、
设备控制、屏幕、浏览器），按 manifest 声明下发；插件负责把这些翻译成模型能用的工具。
所以插件里不该出现 `contentResolver` 一类的直接调用。
