plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Dedicated beta identity keeps the existing debug app and its database intact.
val betaBuild = providers.gradleProperty("cordiactBeta").orNull == "true"
val betaCode = providers.gradleProperty("cordiactBetaVersionCode").orNull
val betaName = providers.gradleProperty("cordiactBetaVersionName").orNull

android { namespace = "com.cordiact.app"; compileSdk = 36
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
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        if (betaBuild) {
            applicationId = "com.cordiact.app.beta"
            versionCode = requireNotNull(betaCode?.toIntOrNull()?.takeIf { it in 2..2_100_000_000 }) {
                "cordiactBetaVersionCode must be in 2..2100000000"
            }
            versionName = requireNotNull(betaName?.takeIf { it.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+-beta[.][0-9]+")) }) {
                "cordiactBetaVersionName must look like 0.1.0-beta.1"
            }
            manifestPlaceholders["cordiactLabel"] = "CordiAct Beta"
        } else {
            manifestPlaceholders["cordiactLabel"] = "CordiAct"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (betaBuild) create("beta") {
            fun requiredEnv(name: String): String = requireNotNull(providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }) {
                "Missing $name for CordiAct beta signing"
            }
            storeFile = file(requiredEnv("CORDIACT_BETA_KEYSTORE_FILE")).also { check(it.isFile) { "Beta keystore missing" } }
            storePassword = requiredEnv("CORDIACT_BETA_STORE_PASSWORD")
            keyAlias = requiredEnv("CORDIACT_BETA_KEY_ALIAS")
            keyPassword = requiredEnv("CORDIACT_BETA_KEY_PASSWORD")
        }
        // Checked-in debug key so CI builds keep one signature across runs and
        // `adb install -r` upgrades the app instead of demanding an uninstall.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            if (betaBuild) signingConfig = signingConfigs.getByName("beta")
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
    // Background sampling + progressive blur (not foreground Modifier.blur).
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    // No image/network/HTML plugin: returned Markdown cannot silently fetch external resources.
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Shizuku client: the privileged channel binds a user service running as the shell uid.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
