plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.cordiact.app"; compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    defaultConfig {
        applicationId = "com.cordiact.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        // Checked-in debug key so CI builds keep one signature across runs and
        // `adb install -r` upgrades the app instead of demanding an uninstall.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":interaction"))
    implementation(project(":model-providers"))
    implementation(project(":search-providers"))
    implementation(project(":mcp-client"))
    implementation(project(":subagent"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    
    // Markdown 渲染（代码高亮 + 表格）
    implementation("com.github.jeziellago:compose-markdown:0.5.4")
    
    // 图片加载
    implementation("io.coil-kt:coil-compose:2.7.0")
    
    // Material Icons Extended（补充图标）
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Shizuku client: the privileged channel binds a user service running as the shell uid.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
