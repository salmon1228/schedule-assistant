import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- 签名配置 ----
// 优先用根目录的 keystore.properties(不进仓库,见 keystore.properties.example);
// 没有就用工程自带的调试签名,这样别人 clone 下来也能直接编译 release 包试玩。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// -PappIdSuffix=.demo → 装一个独立包名的副本(可以和正式版并存,用来截图/验证 release 包)
val appIdSuffix = (findProperty("appIdSuffix") as String?) ?: ""

android {
    namespace = "com.sdh.schedule"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sdh.schedule$appIdSuffix"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("zh", "en")
    }

    // 用工程自带的签名文件,避免 AGP 去写 C:\Users\<user>\.android\debug.keystore
    // (该目录在沙箱/受限环境下不可写)。rootProject 目录在构建时是纯 ASCII 的 junction 路径。
    signingConfigs {
        create("localDebug") {
            storeFile = rootProject.file("_env/local-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            // 首个公开版本先不开混淆,确保行为与 debug 一致;熟悉了再打开
            // (打开时请务必真机回归一遍:设置 → 功能自检)
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.getProperty("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("localDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // 与 Kotlin 1.9.24 严格配对
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 本地中文 OCR(打包版模型,不依赖 Google 服务,可离线识别图片/扫描版 PDF)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}

