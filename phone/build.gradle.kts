plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lobster.lanenav.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lobster.lanenav.phone"
        minSdk = 30        // Android 11+
        targetSdk = 34
        versionCode = 11
        versionName = "0.3.6"
    }

    // 正式签名：仅在提供 keystore 路径时启用（由 build.sh --release 注入环境变量）
    // 密钥库不进仓库，避免泄露；本地调试不受影响。
    val ksPath: String = System.getenv("LANENAV_KEYSTORE") ?: ""

    signingConfigs {
        if (ksPath.isNotEmpty()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("LANENAV_STOREPASS")
                keyAlias = System.getenv("LANENAV_KEYALIAS") ?: "lanenav"
                keyPassword = System.getenv("LANENAV_KEYPASS")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":uikit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
}
