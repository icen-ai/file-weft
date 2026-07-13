plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    implementation(project(":fileweft-spi"))
    implementation(project(":fileweft-core"))

    testImplementation(project(":fileweft-testkit"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
