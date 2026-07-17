plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-spi"))
    implementation(project(":fileweft-core"))
    implementation(libs.jackson.databind)
    implementation(libs.okhttp)

    testImplementation(project(":fileweft-testkit"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
