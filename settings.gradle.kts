
 // Android aplikace se zahrne jen tehdy,
 // je-li k dispozici Android SDK.
val hasAndroidSdk =
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let {
        it.exists() &&
        it.readText().contains("sdk.dir")
    }

pluginManagement {

    val android =
        System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let {
            it.exists() &&
            it.readText().contains("sdk.dir")
        }

    repositories {
        gradlePluginPortal()

        if (android) {
            google()
        }

        mavenCentral()
    }

    // R8 override pro Kotlin 2.4.21.
    //
    // AGP 8.13.2 obsahuje starsi R8, ktery muze
    // hlasit chyby pri parsovani Kotlin metadat.
    //
    // Kotlin 2.4 vyzaduje R8 9.1.29 nebo novejsi.
    //
    // R8 9.1.29 je dostupny v oficialnim
    // Google Cloud Storage Maven repozitari.
    buildscript {

        repositories {
            maven {
                url = uri(
                    "https://storage.googleapis.com/r8-releases/raw"
                )
            }

            google()
            mavenCentral()
        }

        dependencies {
            if (android) {
                classpath(
                    "com.android.tools:r8:9.1.29"
                )
            }
        }
    }
}

dependencyResolutionManagement {

    repositories {
        mavenCentral()

        if (hasAndroidSdk) {
            google()
        }
    }
}

rootProject.name = "pulse4"

// Ciste JVM moduly:
// sestavitelne a testovatelne bez Android SDK.
include(
    ":domain",
    ":analytics",
    ":sharing-policy",
    ":sync-protocol",
    ":provider-router",
    ":connector-garmin-personal",
    ":connector-withings",
    ":training",
    ":fit-import",
    ":backup",
    ":ui-logic",
)

// Android aplikaci zahrneme pouze,
// pokud je dostupne Android SDK.
if (hasAndroidSdk) {
    include(":app")
}
