import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
val testSourceSet = sourceSets.named("test")
val integrationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val rustFsIntegrationTest = tasks.register<Test>("rustFsIntegrationTest") {
    group = "verification"
    description = "Runs the RustFS-backed S3 contract suite exactly once outside the JVM matrix."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include("**/S3StorageAdapterRustFsIntegrationTest.class")
    maxParallelForks = 1
    inputs.property("fileWeftRunRustFsTests", runRustFsIntegration)
    doNotTrackState("The RustFS integration suite must execute against the current object-store state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue("fileWeftRunRustFsTests") == true) {
                "Set FILEWEFT_RUN_RUSTFS_TESTS=true and start the dedicated RustFS test service."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}
