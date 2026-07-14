import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.JavaVersion
import org.gradle.api.attributes.Bundling
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

version = providers.gradleProperty("fileweftVersion").orNull
    ?: throw GradleException("-PfileweftVersion is required for release consumer smoke testing.")

val springBoot2Version = "2.7.18"
val springBoot3Version = "3.5.16"
val junitVersion = "5.11.4"
val kotlinVersion = "2.1.21"
val slf4jBoot2Version = "1.7.36"
val flywayBoot2Version = "8.5.13"
val flywayBoot3Version = "11.7.2"

val expectedFileWeftModules = setOf(
    "fileweft-core",
    "fileweft-spi",
    "fileweft-domain",
    "fileweft-application",
    "fileweft-metadata-api",
    "fileweft-metadata-runtime",
    "fileweft-web-api",
    "fileweft-web-runtime",
    "fileweft-web-spring-boot2-starter",
    "fileweft-web-spring-boot3-starter",
    "fileweft-persistence",
    "fileweft-runtime",
    "fileweft-spring-boot2-starter",
    "fileweft-spring-boot3-starter",
    "fileweft-adapter",
    "fileweft-adapter-micrometer",
    "fileweft-adapter-s3",
    "fileweft-agent",
    "fileweft-testkit",
)
val releaseInventory = configurations.create("releaseInventory") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
val boot3UnmanagedRuntime = configurations.create("boot3UnmanagedRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    expectedFileWeftModules.forEach { moduleName ->
        add(releaseInventory.name, "ai.icen:$moduleName:${project.version}")
    }
    add(boot3UnmanagedRuntime.name, "ai.icen:fileweft-spring-boot3-starter:${project.version}")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "ai.icen.release.smoke"
    version = rootProject.version

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

fun Project.targetJvm(javaVersion: JavaVersion, kotlinTarget: JvmTarget, release: Int) {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(release)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(kotlinTarget)
        compilerOptions.javaParameters.set(true)
    }
}

project(":boot2-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    extra["kotlin.version"] = kotlinVersion
    apply(plugin = "io.spring.dependency-management")
    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBoot2Version")
        }
    }
    dependencies {
        add("implementation", "org.springframework.boot:spring-boot-starter-jdbc")
        add("implementation", "ai.icen:fileweft-spring-boot2-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot2-starter:${rootProject.version}")
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testRuntimeOnly", "com.h2database:h2")
    }

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val verifyBoot2ManagedVersions = tasks.register("verifyBoot2ManagedVersions") {
        group = "verification"
        description = "Asserts the Boot 2 host resolves the supported Kotlin and SLF4J runtime versions."
        inputs.files(runtimeClasspath)

        doLast {
            val resolvedVersions = runtimeClasspath.get().incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.let { module ->
                        "${module.group}:${module.name}" to module.version
                    }
                }
                .toMap()
            require(resolvedVersions["org.jetbrains.kotlin:kotlin-stdlib"] == kotlinVersion) {
                "Boot 2 must resolve kotlin-stdlib $kotlinVersion, but resolved " +
                    resolvedVersions["org.jetbrains.kotlin:kotlin-stdlib"]
            }
            require(resolvedVersions["org.slf4j:slf4j-api"] == slf4jBoot2Version) {
                "Boot 2 must resolve slf4j-api $slf4jBoot2Version, but resolved " +
                    resolvedVersions["org.slf4j:slf4j-api"]
            }
            val flyway = resolvedVersions.filterKeys { coordinate -> coordinate.startsWith("org.flywaydb:") }
            require(
                flyway == mapOf(
                    "org.flywaydb:flyway-core" to flywayBoot2Version,
                    "org.flywaydb:flyway-mysql" to flywayBoot2Version,
                ),
            ) {
                "Boot 2 must resolve one coherent Flyway $flywayBoot2Version runtime, but resolved $flyway"
            }
        }
    }
    tasks.named("check") {
        dependsOn(verifyBoot2ManagedVersions)
    }
}

project(":boot3-consumer") {
    targetJvm(JavaVersion.VERSION_17, JvmTarget.JVM_17, 17)
    dependencies {
        add("implementation", enforcedPlatform("org.springframework.boot:spring-boot-dependencies:$springBoot3Version"))
        add("implementation", "org.springframework.boot:spring-boot-starter-jdbc")
        add("implementation", "ai.icen:fileweft-spring-boot3-starter:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-web-spring-boot3-starter:${rootProject.version}")
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testRuntimeOnly", "com.h2database:h2")
    }

    val runtimeClasspath = configurations.named("runtimeClasspath")
    val verifyBoot3ManagedVersions = tasks.register("verifyBoot3ManagedVersions") {
        group = "verification"
        description = "Asserts the Boot 3 host resolves one coherent Flyway 11 runtime."
        inputs.files(runtimeClasspath)
        doLast {
            val flyway = runtimeClasspath.get().incoming.resolutionResult.allComponents
                .mapNotNull { component -> component.moduleVersion }
                .filter { module -> module.group == "org.flywaydb" }
                .associate { module -> module.name to module.version }
            require(
                flyway == mapOf(
                    "flyway-core" to flywayBoot3Version,
                    "flyway-mysql" to flywayBoot3Version,
                    "flyway-database-postgresql" to flywayBoot3Version,
                ),
            ) {
                "Boot 3 must resolve one coherent Flyway $flywayBoot3Version runtime, but resolved $flyway"
            }
        }
    }
    tasks.named("check") {
        dependsOn(verifyBoot3ManagedVersions)
    }
}

project(":library-consumer") {
    targetJvm(JavaVersion.VERSION_1_8, JvmTarget.JVM_1_8, 8)
    dependencies {
        add("implementation", "ai.icen:fileweft-spi:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-agent:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-persistence:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-adapter-micrometer:${rootProject.version}")
    }
}

val verifyReleaseInventory = tasks.register("verifyReleaseInventory") {
    group = "verification"
    description = "Resolves the exact public FileWeft module inventory from the configured Maven repository."
    inputs.property("expectedFileWeftModules", expectedFileWeftModules.sorted())
    inputs.files(releaseInventory)

    doLast {
        val resolvedFileWeftModules = releaseInventory.resolvedConfiguration.resolvedArtifacts
            .map { artifact -> artifact.moduleVersion.id }
            .filter { component -> component.group == "ai.icen" && component.version == project.version.toString() }
            .map { component -> component.name }
            .toSet()
        require(resolvedFileWeftModules == expectedFileWeftModules) {
            "Resolved FileWeft inventory differs from the release contract; " +
                "missing=${expectedFileWeftModules - resolvedFileWeftModules}, " +
                "unexpected=${resolvedFileWeftModules - expectedFileWeftModules}."
        }
    }
}

val verifyUnmanagedBoot3FlywayRuntime = tasks.register("verifyUnmanagedBoot3FlywayRuntime") {
    group = "verification"
    description = "Proves the published Boot 3 starter keeps Flyway coherent even without a host BOM."
    inputs.files(boot3UnmanagedRuntime)
    doLast {
        val flyway = boot3UnmanagedRuntime.incoming.resolutionResult.allComponents
            .mapNotNull { component -> component.moduleVersion }
            .filter { module -> module.group == "org.flywaydb" }
            .associate { module -> module.name to module.version }
        require(
            flyway == mapOf(
                "flyway-core" to flywayBoot3Version,
                "flyway-mysql" to flywayBoot3Version,
                "flyway-database-postgresql" to flywayBoot3Version,
            ),
        ) {
            "Unmanaged Boot 3 consumer must resolve one coherent Flyway $flywayBoot3Version runtime, but resolved $flyway"
        }
    }
}

tasks.register("releaseSmoke") {
    group = "verification"
    description = "Verifies the release inventory, compiles independent consumers, and starts Boot 2/3 hosts with host-owned JDBC."
    dependsOn(verifyReleaseInventory)
    dependsOn(verifyUnmanagedBoot3FlywayRuntime)
    dependsOn(subprojects.map { consumerProject -> consumerProject.tasks.named("build") })
}
