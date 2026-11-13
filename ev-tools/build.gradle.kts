plugins {
    // 版本在根构建里钉死（AGP 9 自带 Kotlin，不能再套 kotlin.android）。
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugin.tools"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugin.tools"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 宿主在运行时提供这些包；打进容器会造成类身份重复。
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("UnitTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    // 只用坐标。settings.gradle.kts 里的 includeBuild 把它们替换成宿主源码模块，
    // 所以编译期拿到的是真实契约，而不是一份复制粘贴的副本。
    // 版本号在替换时不参与匹配（按 group:name），写与宿主默认一致的值最不容易让人困惑。
    compileOnly("io.github.buanys:host-api:0.0.1-SNAPSHOT")
    compileOnly("io.github.buanys:loader:0.0.1-SNAPSHOT")

    // 单测要真的跑 ToolRegistry / ServiceKey 这些类型，编译期的 compileOnly 在运行时不存在。
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.github.buanys:host-api:0.0.1-SNAPSHOT")
    testImplementation("io.github.buanys:loader:0.0.1-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}


// 元数据两处都要：dist 里的 sidecar 供自检，容器里的 META-INF/plugin.json 供宿主——
// 宿主优先读内嵌那份，所以一个 jar 自己就是完整的可分发插件。
val pluginMetadata = """
            {
              "id": "ev.buan.plugin.tools",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugin.tools.ToolRegistryPlugin",
              "apiVersion": 1,
              "hostApi": ">=1.0.0 <2.0.0",
              "permissions": []
            }
        """.trimIndent()

val pluginMetadataFile = layout.buildDirectory.file("plugin-metadata/plugin.json")

val writePluginMetadata = tasks.register("writePluginMetadata") {
    outputs.file(pluginMetadataFile)
    doLast {
        val file = pluginMetadataFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(pluginMetadata)
    }
}

val pluginDist = layout.buildDirectory.dir("dist")

// ---------------------------------------------------------------- 产物命名
//
// 文件名给人看，id 给机器看，两者分开：
//
//   产物  「工具总线-core.jar」/「工具总线-core.json」
//   id    ev.buan.plugin.tools
//   入口  ev.buan.plugin.tools.ToolRegistryPlugin
//
// 中文在前是为了在 /sdcard/EV/mods 里一眼认出这是什么东西；
// `-` 后面是**分类的英文名**，见 README 的分层表：
//   core  骨架：没有它系统不成立，且**不许声明任何权限**
//   base  能力：唯一可以声明 HostPermission 的一层
//   feature / integration / resource  见 README
//
// **jar 与 sidecar 必须同名同前缀**：宿主按「jar 去掉扩展名 + .json」找 sidecar
// （PluginMetadataReader.readSidecarMetadata），两个名字对不上，插件会被当成没有元数据。
val pluginDisplayName = "工具总线"
val pluginCategory = "core"
val artifactBase = "$pluginDisplayName-$pluginCategory"

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug", writePluginMetadata)
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/${project.name}-debug.apk"))) {
        include("classes*.dex")
    }
    from(pluginMetadataFile) {
        into("META-INF")
        rename { "plugin.json" }
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("$artifactBase.jar")
    // 改名之后 dist 里会留着上一次的旧产物，而 bundlePlugin 的自检要求
    // "恰好一个 jar + 一个 sidecar"——旧文件会让改名这件事在构建期失败。
    // 每次打包先清空，改名就只是改名。
    doFirst {
        pluginDist.get().asFile.listFiles()?.forEach { it.delete() }
    }
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce $artifactBase.jar / .json for EV/mods"
    dependsOn("bundlePluginJar")
    doLast {
        // sidecar 是运行时唯一能读到的元信息。hostApi 用范围而不是整数：
        // 声明了范围，整数门就完全退出（见宿主仓库 docs/sdk/versioning.md）。
        
        File(pluginDist.get().asFile, "$artifactBase.json").writeText(pluginMetadata)
    }
}

// 让 plugin-verify 知道分类：它据此检查「只有 base 可以声明权限」。
extra["evPluginCategory"] = pluginCategory

// 与宿主仓库里同名脚本一致的不变量（配置缓存声明 + 产物自检 + 分层检查）。
apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
