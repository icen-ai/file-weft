plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-core"))
    api(project(":fileweft-spi"))
    implementation(project(":fileweft-application"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
