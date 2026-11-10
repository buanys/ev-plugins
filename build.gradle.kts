// 版本钉在根构建里：AGP 9 自带 Kotlin，但用的是构建脚本 classpath 上那个 KGP 版本。
// classpath 上没有 KGP 时它会回退到内置的 2.2.0，而宿主用 2.4.20 编译，
// 对着 host-api 的 classes.jar 会报
//   "Module was compiled with an incompatible version of Kotlin ... expected 2.2.0"
// 这两行 apply false 的作用就是把 KGP 放上 classpath，而不是应用它。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
