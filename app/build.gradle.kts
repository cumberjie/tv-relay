import java.io.File

plugins {
    id("com.android.application")
}

android {
    namespace = "com.skyeward.tvrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skyeward.tvrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
    }

    // 统一签名通道（照抄 yuhu 项目）。
    // CI 由 GitHub Secrets 注入环境变量：
    //   KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
    // 保证每次构建出的 APK 签名一致，电视上可直接覆盖安装升级。
    // 本仓库是公开仓库，密钥库只存在于 Secrets 里，绝不写入代码仓库。
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_FILE")
            if (path != null && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                storeType = "PKCS12"
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            // R8 代码压缩 + 资源裁剪：减小 APK 与安装占用。
            // 项目零反射，manifest 声明的组件 AGP 会自动 keep。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (File(System.getenv("KEYSTORE_FILE") ?: "").exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
