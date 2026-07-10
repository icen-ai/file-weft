plugins {
    id("fileweft.jvm17-library")
    application
}

application {
    mainClass.set("com.fileweft.dev.FileWeftDevApplicationKt")
}

dependencies {
    implementation(project(":fileweft-spring-boot3-starter"))
    implementation(project(":fileweft-adapter-s3"))
    implementation(project(":fileweft-persistence"))
    implementation(libs.spring.boot3.starter.web)
    implementation(libs.spring.boot3.starter.jdbc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.flyway.core)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.spring.boot3.starter.test)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
