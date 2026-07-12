import org.gradle.api.tasks.testing.Test

plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-web-runtime"))
    api(libs.spring.boot2.starter.web)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot2.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Spring Boot 2.7 locks Mockito/Byte Buddy to a generation that predates
// Java 21 class-file recognition. Inline mocking is test-only and is needed
// for final Kotlin application services; allow that locked Byte Buddy version
// to run on the repository's Java 21/25 compatibility test workers.
tasks.withType<Test>().configureEach {
    systemProperty("net.bytebuddy.experimental", "true")
}
