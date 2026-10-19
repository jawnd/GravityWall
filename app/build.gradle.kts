plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gravitywall.lock"
    // libxposed 的产物要求 compileSdk >= 37
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.gravitywall.lock"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // AGP 9 内置 Kotlin 支持：不再需要（也不允许）单独应用 kotlin.android 插件，
    // JVM 目标由 compileOptions 决定。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // 编译期依赖：模块 API（不打包进 APK）
    compileOnly("io.github.libxposed:api:102.0.0")
    // 运行期依赖：与框架/应用通信（远程偏好、XposedProvider）
    implementation("io.github.libxposed:service:102.0.0")

    // 界面：Compose（用 AndroidX BOM 统一版本；JetBrains Compose 的 material3 只有 alpha）
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.3")
}
