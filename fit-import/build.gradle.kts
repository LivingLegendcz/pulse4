plugins { kotlin("jvm") }

dependencies {
    api(project(":domain"))
    implementation(project(":analytics"))
    // Oficiální Garmin FIT SDK, verze uzamčená.
    implementation("com.garmin:fit:21.218.0")
}
