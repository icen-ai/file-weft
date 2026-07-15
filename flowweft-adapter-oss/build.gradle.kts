import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("fileweft.jvm8-library")
}

dependencies {
    api(project(":fileweft-spi"))
    implementation(project(":fileweft-core"))
    implementation(libs.aliyun.oss) {
        // Alibaba's optional tracing/auth stack currently requests SLF4J 2.x.
        // Keep this Java 8 adapter neutral across Boot 2 (1.7) and Boot 3 (2.x):
        // publish the minimum API and let an application platform select newer.
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(libs.slf4j.api)
    constraints {
        implementation(libs.opentelemetry.api) {
            because("aliyun-sdk-oss 3.18.5 transitively selects a vulnerable OpenTelemetry API version")
        }
        implementation(libs.bouncycastle.provider) {
            because("aliyun-sdk-oss 3.18.5 transitively selects vulnerable Bouncy Castle 1.79")
        }
    }

    testImplementation(project(":fileweft-testkit"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val runOssIntegration = providers.environmentVariable("FLOWWEFT_RUN_OSS_TESTS")
    .map { value -> value == "true" }
    .orElse(false)
val runOssOverwriteIntegration = providers.environmentVariable("FLOWWEFT_RUN_OSS_OVERWRITE_TESTS")
    .map { value -> value == "true" }
    .orElse(false)
val runAnyOssIntegration = runOssIntegration.zip(runOssOverwriteIntegration) { contract, overwrite ->
    contract || overwrite
}
val testSourceSet = sourceSets.named("test")
val integrationJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val ossIntegrationTest = tasks.register<Test>("ossIntegrationTest") {
    group = "verification"
    description = "Runs the Alibaba Cloud OSS contract suite exactly once outside the JVM matrix."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(integrationJavaLauncher)
    include(
        "**/OssStorageAdapterIntegrationTest.class",
        "**/OssOverwriteGuardIntegrationTest.class",
    )
    maxParallelForks = 1
    inputs.property("flowWeftRunOssTests", runOssIntegration)
    inputs.property("flowWeftRunOssOverwriteTests", runOssOverwriteIntegration)
    doNotTrackState("The OSS integration suite executes against the current remote bucket state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(runAnyOssIntegration.get()) {
                "Set FLOWWEFT_RUN_OSS_TESTS=true or FLOWWEFT_RUN_OSS_OVERWRITE_TESTS=true " +
                    "and provide the dedicated OSS test credentials."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}
