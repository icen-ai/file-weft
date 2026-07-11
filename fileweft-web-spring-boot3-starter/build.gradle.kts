plugins {
    id("fileweft.jvm17-library")
}

dependencies {
    api(project(":fileweft-web-runtime"))
    api(libs.spring.boot3.starter.web)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot3.test)
    testImplementation(libs.spring.boot3.starter.test)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
