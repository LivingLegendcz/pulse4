plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":domain"))
    api(project(":analytics"))
    api(project(":sharing-policy"))
    api(project(":sync-protocol"))
    api(project(":provider-router"))
    api(project(":training"))
}
