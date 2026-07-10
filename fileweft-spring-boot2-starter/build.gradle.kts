plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-runtime"))
    implementation(project(":fileweft-adapter"))
    api(libs.spring.boot2.autoconfigure)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot2.test)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
