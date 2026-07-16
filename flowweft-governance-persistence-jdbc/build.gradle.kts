plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-governance-runtime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.h2)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
