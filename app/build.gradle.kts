
import java.util.Properties

// Android aplikace Pulse4.
// Release build pro Google Play s podporou podepisovani
// a nativnich debug symbolu.

plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.pulse4.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.pulse4"

        // Android 9+
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Play vyzaduje pro kazde nove vydani
        // vyssi versionCode nez u predchoziho vydani.
        versionCode = 10
        versionName = "0.2.5"

        // HTTPS App Link pro navrat z Withings OAuth.
        // Vlastni domena se statickym assetlinks.json.
        val host = (
            project.findProperty("pulse4.withingsHost") as String?
        ) ?: "pulse4.example.invalid"

        manifestPlaceholders["withingsHost"] = host

        buildConfigField(
            "String",
            "WITHINGS_REDIRECT",
            "\"https://$host/withings/callback\""
        )

        // Podporovane architektury.
        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "x86_64"
            )
        }
    }

    signingConfigs {
        // Debug signing.
        val rootKeystore = file("${rootDir}/debug.keystore")
        val homeKeystore = file(
            "${System.getProperty("user.home")}/.android/debug.keystore"
        )

        val chosenKeystore = if (rootKeystore.exists()) {
            rootKeystore
        } else {
            homeKeystore
        }

        if (chosenKeystore.exists()) {
            create("debugConfig") {
                storeFile = chosenKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        // Release signing:
        // local.properties nebo GitHub Actions environment PULSE4_*.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")

            if (f.exists()) {
                f.inputStream().use { load(it) }
            }
        }

        fun secret(prop: String, env: String): String? {
            return localProps.getProperty(prop)
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)
                    ?.takeIf { it.isNotBlank() }
        }

        val releaseKeystore = file(
            secret(
                "release.storeFile",
                "PULSE4_STORE_FILE"
            ) ?: "${rootDir}/release.keystore"
        )

        val releaseStorePass = secret(
            "release.storePassword",
            "PULSE4_STORE_PASSWORD"
        )

        if (releaseKeystore.exists() && releaseStorePass != null) {
            create("releaseConfig") {
                storeFile = releaseKeystore
                storePassword = releaseStorePass

                keyAlias = secret(
                    "release.keyAlias",
                    "PULSE4_KEY_ALIAS"
                ) ?: "pulse4key"

                keyPassword = secret(
                    "release.keyPassword",
                    "PULSE4_KEY_PASSWORD"
                ) ?: releaseStorePass
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("debugConfig")?.let {
                signingConfig = it
            }
        }

        release {
            // Podepisovani pro Google Play.
            signingConfigs.findByName("releaseConfig")?.let {
                signingConfig = it
            }

            // Optimalizace a obfuskace.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )

            // Native Debug Symbols pro Google Play Console.
            // Pomahaji analyzovat nativni pady a ANR.
            // SYMBOL_TABLE ma mensi velikost nez FULL.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Nativni knihovny zustavaji v APK nekomprimovane.
    // 16KB page compatibility je nutne overit i u samotnych .so.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Interni moduly Pulse4.
    implementation(project(":domain"))
    implementation(project(":analytics"))
    implementation(project(":sharing-policy"))
    implementation(project(":sync-protocol"))
    implementation(project(":rns-core"))
    implementation(project(":provider-router"))
    implementation(project(":connector-garmin-personal"))
    implementation(project(":training"))
    implementation(project(":fit-import"))
    implementation(project(":backup"))
    implementation(project(":connector-withings"))
    implementation(project(":ui-logic"))

    // Jetpack Compose.
    val compose = platform(
        "androidx.compose:compose-bom:2026.09.00"
    )

    implementation(compose)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")

    // Room a sifrovana databaze.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite-ktx:2.5.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")

    // Background work, HTTP, Health Connect.
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
    )

    // MapLibre.
    implementation("org.maplibre.gl:android-sdk:13.7.1")

    // QR generovani a skenovani.
    implementation("com.google.zxing:core:3.5.4")
    implementation(
        "com.google.android.gms:play-services-code-scanner:16.1.0"
    )
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Testy.
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")

    // Instrumentovane emulator testy.
    androidTestImplementation(compose)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
}
