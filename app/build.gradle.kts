import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名配置：本地 keystore.properties（已被 .gitignore 排除，含密码，绝不提交）
// CI 构建 Release 时由工作流从 GitHub Secrets 生成同名文件
fun loadReleaseSigning(): Properties? {
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return null
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return props
}

android {
    namespace = "com.skyeward.tvrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skyeward.tvrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val props = loadReleaseSigning()
            if (props != null) {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 代码压缩 + 资源裁剪：减小 APK 与安装占用。
            // 项目零反射，manifest 声明的组件 AGP 会自动 keep。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (loadReleaseSigning() != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
