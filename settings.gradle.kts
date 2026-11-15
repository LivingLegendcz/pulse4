
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
