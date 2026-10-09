// 根构建脚本:只声明插件版本,AGP 8.5.2 / Kotlin 1.9.24 与本机已有依赖缓存匹配
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
