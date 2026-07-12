import org.gradle.api.tasks.testing.Test

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

val runRustFsIntegration = providers.environmentVariable("FILEWEFT_RUN_RUSTFS_TESTS")
    .map { value -> value == "true" }
    .orElse(false)

tasks.withType<Test>().configureEach {
    inputs.property("fileWeftRunRustFsTests", runRustFsIntegration)
    if (runRustFsIntegration.get()) {
        doNotTrackState("The enabled RustFS integration suite must execute against the current object-store state.")
    }
}
