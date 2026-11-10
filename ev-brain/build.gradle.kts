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

val pluginDist = layout.buildDirectory.dir("dist")

val pluginDisplayName = "主循环"
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
              "id": "ev.buan.plugin.brain",
              "version": "1.0.0",
              "entryClass": "ev.buan.plugin.brain.AgentBrainPlugin",
              "apiVersion": 1,
              "hostApi": ">=1.0.0 <2.0.0",
              "permissions": []
            }
        """.trimIndent()
        File(pluginDist.get().asFile, "$artifactBase.json").writeText(metadata)
    }
}

extra["evPluginCategory"] = pluginCategory

apply(from = rootProject.file("gradle/plugin-verify.gradle.kts"))
