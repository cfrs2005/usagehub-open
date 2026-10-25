plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号只有一个可信来源：git tag（v0.1.6）。CI 用 -P 传进来，本地裸构建才回落到下面的默认值。
// 默认值请与「最近一次已发布的 Release」保持一致，避免本地产物的版本号跑到线上前面。
val appVersionName: String = providers.gradleProperty("usagehubVersionName").orNull ?: "0.1.6"
val appVersionCode: Int = providers.gradleProperty("usagehubVersionCode").orNull?.toIntOrNull() ?: 106

android {
    namespace = "com.usagehub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.usagehub.app"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signingConfigs.names.contains("release")) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            buildConfigField("boolean", "KIOSK_MODE", "false")
        }
        create("kiosk") {
            dimension = "mode"
            applicationIdSuffix = ".kiosk"
            versionNameSuffix = "-kiosk"
            buildConfigField("boolean", "KIOSK_MODE", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        disable += "AllowBackup"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
