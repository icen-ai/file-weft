plugins {
    id("fileweft.jvm17-library")
    application
}

application {
    mainClass.set("com.fileweft.dev.FileWeftDevApplicationKt")
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

val devUiE2e = tasks.register<Exec>("devUiE2e") {
    group = "verification"
    description = "Runs the Playwright acceptance suite against the running FileWeft development Compose stack."
    workingDir(layout.projectDirectory.dir("web"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm", "run", "test:e2e")
}

if (runDevUiE2e.get()) {
    tasks.named("check") {
        dependsOn(devUiE2e)
    }
}
