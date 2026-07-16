plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-agent-api"))
    api(project(":flowweft-agent-runtime"))
    api(project(":flowweft-workflow-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
