import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("fileweft.jvm17-library")
    application
}

application {
    mainClass.set("ai.icen.fw.dev.FileWeftDevApplicationKt")
}

dependencies {
    implementation(project(":fileweft-spring-boot3-starter"))
    implementation(project(":fileweft-web-spring-boot3-starter"))
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

val runDevUiE2e = providers.environmentVariable("FILEWEFT_RUN_DEV_UI_E2E")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

val runDevApiE2e = providers.environmentVariable("FILEWEFT_RUN_DEV_E2E")
    .map { it == "true" }
    .orElse(false)
val testSourceSet = sourceSets.named("test")
val acceptanceJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val devApiAcceptanceTest = tasks.register<Test>("devApiAcceptanceTest") {
    group = "verification"
    description = "Runs the Dev API acceptance suite exactly once against the Compose stack."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(acceptanceJavaLauncher)
    include("**/DevAcceptanceIntegrationTest.class")
    maxParallelForks = 1
    inputs.property("fileWeftRunDevApiE2e", runDevApiE2e)
    doNotTrackState("The Dev API acceptance suite must execute against the current Compose stack.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue("fileWeftRunDevApiE2e") == true) {
                "Set FILEWEFT_RUN_DEV_E2E=true after starting the complete FileWeft development Compose stack."
            }
        },
    )
    shouldRunAfter(tasks.named("test"))
}

val devUiE2e = tasks.register<Exec>("devUiE2e") {
    group = "verification"
    description = "Runs the Playwright acceptance suite against the running FileWeft development Compose stack."
    workingDir(layout.projectDirectory.dir("web"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm", "run", "test:e2e")
    inputs.property("fileWeftRunDevUiE2e", runDevUiE2e)
    doNotTrackState("The Playwright acceptance suite must execute against the current Compose stack and browser state.")
    doFirst(
        org.gradle.api.Action<org.gradle.api.Task> {
            require(inputs.properties.getValue("fileWeftRunDevUiE2e") == true) {
                "Set FILEWEFT_RUN_DEV_UI_E2E=true after installing the locked Playwright dependencies and starting Compose."
            }
        },
    )
    mustRunAfter(devApiAcceptanceTest)
}
