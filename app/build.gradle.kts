plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kai.videostitcher"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.kai.videostitcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 22
        versionName = "1.9.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用本机调试证书签名：Android 要求所有 APK 必须带签名才能安装，
            // 未签名的 release 包在手机上会报"安装包缺乏开发者证书"。
            // 调试证书长期稳定存在（~/.android/debug.keystore），同一台机器
            // 后续构建签名一致，可直接覆盖升级，不必每次卸载重装。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/DEPENDENCIES"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("org.mp4parser:muxer:1.9.56")

    implementation("androidx.media3:media3-transformer:1.11.0")
    implementation("androidx.media3:media3-effect:1.11.0")

    // ffmpeg-kit 社区续维护版（官方 com.arthenica 2025-01 退役）。
    // full-gpl 含 libx264（GPL-3.0），包名/API 与官方一致（com.arthenica.ffmpegkit.*），
    // 16KB 页对齐；ABI 仅 arm64-v8a/x86_64，32 位设备由 Merger.ffmpegAvailable() 降级
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7")
    // ffmpeg-kit 的运行时依赖，fork 的 POM 没带，必须手动声明（缺了会 NoClassDefFoundError）
    implementation("com.arthenica:smart-exception-java:0.2.0")

    testImplementation("junit:junit:4.13.2")
}
