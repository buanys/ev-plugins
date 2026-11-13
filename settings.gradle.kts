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
// 默认同级目录（../EV 对应 EV 仓库与插件仓库并排 checkout 的常见布局）；
// 别的机器或 CI 上用 -PevHost=/path/to/EV 或环境变量 EV_HOST 覆盖。
// 将来换成真正的发布坐标时，这两条路都在 EV 仓库 docs/sdk/publishing.md 第 3 节。
val hostTree = providers.gradleProperty("evHost").orNull
    ?: System.getenv("EV_HOST")
    ?: "../EV"
includeBuild(hostTree)

include(":ev-tools")
include(":ev-log")
include(":ev-brain")

