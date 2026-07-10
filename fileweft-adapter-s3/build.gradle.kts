plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-spi"))
    implementation(project(":fileweft-core"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.url.connection.client)

    testImplementation(project(":fileweft-testkit"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
