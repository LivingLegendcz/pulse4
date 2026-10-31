plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":domain"))
    api(project(":sharing-policy"))
    api(project(":sync-protocol"))
    api(project(":provider-router"))
}
