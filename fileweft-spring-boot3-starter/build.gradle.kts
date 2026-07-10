plugins {
    id("fileweft.jvm17-library")
}

dependencies {
    api(project(":fileweft-runtime"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
