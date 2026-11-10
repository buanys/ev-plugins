pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ev-plugins"

// 宿主源码树。插件用坐标声明依赖，这里把它们替换成本地模块——
// 不发布、不下载 aar，但编译期拿到的就是宿主真实的契约。
//
// 换成别人机器或 CI 时，只改这一行的路径（或者将来换成真正的发布坐标，
// 见 EV 仓库 docs/sdk/publishing.md 第 3 节的两条路）。
includeBuild("/workspace/EV")

include(":ev-tools")
