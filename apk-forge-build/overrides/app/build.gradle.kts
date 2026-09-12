plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "net.apkforge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.apkforge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        multiDexEnabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // On-device APK/Smali rebuild engine and in-process APK signing/verification.
    implementation("org.apktool:apktool-lib:2.12.1")
    implementation("com.android.tools.build:apksig:8.13.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
