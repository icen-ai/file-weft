import org.gradle.api.tasks.testing.Test

plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":flowweft-workflow-web-runtime"))
    compileOnlyApi(libs.spring.boot2.starter.web)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot2.starter.web)
    testImplementation(libs.spring.boot2.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    systemProperty("net.bytebuddy.experimental", "true")
}
