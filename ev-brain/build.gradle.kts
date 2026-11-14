plugins {
    id("com.android.application")
}

android {
    namespace = "ev.buan.plugin.brain"
    compileSdk = 37

    defaultConfig {
        applicationId = "ev.buan.plugin.brain"
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
    // 主循环与管线用协程；JVM 单测要真实现。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.json:json:20260814")
}

// The agent runtime ships inside the container: `bin/ev-agentd` is extracted by
// the plugin on first use. Point `-PevAgentd=` (or EV_AGENTD) at another build
// when testing a local one.
val daemonBinary = providers.gradleProperty("evAgentd").orNull
    ?: System.getenv("EV_AGENTD")
    ?: "../ev-agent/target/aarch64-unknown-linux-musl/release/ev-agentd"


// 元数据两处都要：dist 里的 sidecar 供自检，容器里的 META-INF/plugin.json 供宿主——
// 宿主优先读内嵌那份，所以一个 jar 自己就是完整的可分发插件。
val pluginMetadata = """
            {
              "id": "ev.buan.plugin.brain",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugin.brain.AgentBrainPlugin",
              "apiVersion": 1,
              "hostApi": ">=1.0.0 <2.0.0",
              "permissions": ["PLUGIN_STORAGE"]
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

val pluginDisplayName = "主循环"
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
    val binary = rootProject.file(daemonBinary)
    if (!binary.isFile) {
        throw GradleException(
            "找不到 agent 进程：${binary.absolutePath}\n" +
                "先在 ev-agent 里执行 cargo build --release --target aarch64-unknown-linux-musl，" +
                "或用 -PevAgentd=<path> 指定。",
        )
    }
    from(binary) {
        into("bin")
        rename { "ev-agentd" }
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("$artifactBase.jar")
    doFirst {
        pluginDist.get().asFile.listFiles()?.forEach { it.delete() }
    }
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce $artifactBase.jar / .json for EV/mods"
    dependsOn("bundlePluginJar")
    doLast {
        
        File(pluginDist.get().asFile, "$artifactBase.json").writeText(pluginMetadata)
    }
}

extra["evPluginCategory"] = pluginCategory

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
