plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-application"))
    api(project(":fileweft-web-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
