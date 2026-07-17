plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-workflow-runtime"))
    api(project(":flowweft-workflow-reference-templates"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
