plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-reliability-api"))
    api(project(":flowweft-reliability-runtime"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
