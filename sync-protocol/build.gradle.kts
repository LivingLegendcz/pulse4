plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":domain"))
    api(project(":sharing-policy"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
}
