plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.cordiact.app"; compileSdk = 35
    buildFeatures { compose = true }
    defaultConfig { applicationId = "com.cordiact.app"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integration"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
