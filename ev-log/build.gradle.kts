plugins {
    // 版本在根构建里钉死（AGP 9 自带 Kotlin，不能再套 kotlin.android）。
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugin.log"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugin.log"
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
    compileOnly("io.github.buanys:host-api:0.0.1-SNAPSHOT")
    compileOnly("io.github.buanys:loader:0.0.1-SNAPSHOT")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.github.buanys:host-api:0.0.1-SNAPSHOT")
    testImplementation("io.github.buanys:loader:0.0.1-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    // 插件本体用平台自带的 org.json（永不打包），JVM 单测需要真实现。
    testImplementation("org.json:json:20260814")
}

val pluginDist = layout.buildDirectory.dir("dist")

// 产物命名：中文给人看，分类英文名区分（见 README）。core = 骨架层，不许声明系统数据权限。
val pluginDisplayName = "事件流"
val pluginCategory = "core"
val artifactBase = "$pluginDisplayName-$pluginCategory"

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/${project.name}-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("$artifactBase.jar")
    // 改名之后 dist 里会留着上一次的旧产物，而自检要求"恰好一个 jar + 一个 sidecar"。
    doFirst {
        pluginDist.get().asFile.listFiles()?.forEach { it.delete() }
    }
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce $artifactBase.jar / .json for EV/mods"
    dependsOn("bundlePluginJar")
    doLast {
        val metadata = """
            {
              "id": "ev.buan.plugin.log",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugin.log.SessionLogPlugin",
              "apiVersion": 1,
              "hostApi": ">=1.0.0 <2.0.0",
              "permissions": ["PLUGIN_STORAGE"]
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "$artifactBase.json").writeText(metadata)
    }
}

// 让 plugin-verify 知道分类：它据此检查「只有 base 可以声明系统数据权限」。
extra["evPluginCategory"] = pluginCategory

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
