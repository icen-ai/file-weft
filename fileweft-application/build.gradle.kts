plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-core"))
    api(project(":fileweft-domain"))
    api(project(":fileweft-spi"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
