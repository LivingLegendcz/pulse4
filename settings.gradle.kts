 // Android aplikace se zahrne jen tehdy, je-li k dispozici Android SDK.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let {
        it.exists() && it.readText().contains("sdk.dir")
    }

pluginManagement {
    val android = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let {
            it.exists() && it.readText().contains("sdk.dir")
        }

    repositories {
        gradlePluginPortal()
        if (android) google()
        mavenCentral()
    }

    // Override vestaveneho R8 z Android Gradle Pluginu 8.13.2.
    // Kotlin 2.4.x vyzaduje R8 9.1.29 nebo novejsi.
    // Resi warningy pri zpracovani Kotlin metadat.
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        if (hasAndroidSdk) google()
    }
}

rootProject.name = "pulse4"

// Ciste JVM moduly: sestavitelne a testovatelne bez Android SDK.
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

// Android aplikaci pridame pouze pri dostupnem Android SDK.
if (hasAndroidSdk) {
    include(":app")
}
