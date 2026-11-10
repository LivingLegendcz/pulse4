// POZOR: tento modul nebyl v prostředí, kde vznikl, sestaven (chyběl Android SDK a přístup na dl.google.com / chaquo.com).
// Verze závislostí je třeba před prvním sestavením ověřit a případně zvýšit; viz README.
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.pulse4.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pulse4"
        minSdk = 28            // Android 9, návrhové minimum
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // HTTPS App Link pro návrat z Withings OAuth: vlastní doména se statickým assetlinks.json (nikoli token broker).
        val host = (project.findProperty("pulse4.withingsHost") as String?) ?: "pulse4.example.invalid"
        manifestPlaceholders["withingsHost"] = host
        buildConfigField("String", "WITHINGS_REDIRECT", "\"https://$host/withings/callback\"")

        // arm64 pro telefony, x86_64 pro emulátor; nic dalšího.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }

    // 16KB stránky: nativní knihovny se nesmějí komprimovat v APK (vyžaduje zarovnání).
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }


dependencies {
    implementation(project(":domain"))
    implementation(project(":analytics"))
    implementation(project(":sharing-policy"))
    implementation(project(":sync-protocol"))
    implementation(project(":provider-router"))
    implementation(project(":connector-garmin-personal"))
    implementation(project(":training"))
    implementation(project(":fit-import"))
    implementation(project(":backup"))
    implementation(project(":connector-withings"))
    implementation(project(":ui-logic"))

    val compose = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    // Šifrovaná SQLite (Room samo databázi nešifruje).
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite-ktx:2.5.2")

    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Mapa (vykreslovací komponenta); zdroj dlaždic a jeho licenci volí uživatel v nastavení.
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // QR kód: generování (ZXing core) a skenování (Google code scanner; bez Play Services lze kód vložit textem).
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
