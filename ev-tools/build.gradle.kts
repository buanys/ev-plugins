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

val pluginDist = layout.buildDirectory.dir("dist")

val bundlePluginJar = tasks.register<Zip>("bundlePluginJar") {
    group = "distribution"
    dependsOn("assembleDebug")
    from(zipTree(layout.buildDirectory.file("outputs/apk/debug/${project.name}-debug.apk"))) {
        include("classes*.dex")
    }
    destinationDirectory.set(pluginDist)
    archiveFileName.set("${project.name}.jar")
}

tasks.register("bundlePlugin") {
    group = "distribution"
    description = "Produce ev-tools.jar and ev-tools.json for EV/mods"
    dependsOn("bundlePluginJar")
    doLast {
        // sidecar 是运行时唯一能读到的元信息。hostApi 用范围而不是整数：
        // 声明了范围，整数门就完全退出（见宿主仓库 docs/sdk/versioning.md）。
        val metadata = """
            {
              "id": "ev.buan.plugin.tools",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugin.tools.ToolRegistryPlugin",
              "apiVersion": 1,
              "hostApi": ">=1.0.0 <2.0.0",
              "permissions": []
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "${project.name}.json").writeText(metadata)
    }
}

// 与宿主仓库里同名脚本一致的两条不变量（配置缓存声明 + 产物自检）。
apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
