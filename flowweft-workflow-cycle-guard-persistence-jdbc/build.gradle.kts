plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-workflow-cycle-guard-runtime"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
