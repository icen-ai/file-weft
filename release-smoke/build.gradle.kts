import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.JavaVersion
import org.gradle.api.attributes.Bundling
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.21" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

data class ReleasePublicationInventoryEntry(
    val artifactId: String,
    val artifactKind: String,
    val lineage: String,
    val jvmBaseline: Int,
)

fun readPublicationInventory(inventoryFile: File): List<ReleasePublicationInventoryEntry> {
    require(inventoryFile.isFile && inventoryFile.length() > 0L) {
        "Publication inventory is missing or empty: ${inventoryFile.absolutePath}"
    }
    val normalized = inventoryFile.readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val rawLines = normalized.split('\n')
    val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
    val header = "artifactId\tartifactKind\tlineage\tjvmBaseline"
    require(lines.firstOrNull() == header) {
        "Publication inventory must start with the exact TSV header '$header'."
    }
    val entries = lines.drop(1).mapIndexed { index, line ->
        val lineNumber = index + 2
        val columns = line.split('\t', limit = 5)
        require(columns.size == 4 && columns.all { column -> column.isNotEmpty() && column == column.trim() }) {
            "Publication inventory line $lineNumber must contain exactly four non-padded TSV fields."
        }
        val artifactId = columns[0]
        val jvmBaseline = columns[3].toIntOrNull()
        require(artifactId.length in 3..80 && Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$").matches(artifactId)) {
            "Publication inventory line $lineNumber has an invalid artifact ID '$artifactId'."
        }
        require(columns[1] in setOf("jar", "platform")) {
            "Publication inventory line $lineNumber has unsupported artifact kind '${columns[1]}'."
        }
        require(columns[2] in setOf("legacy-physical", "new-physical")) {
            "Publication inventory line $lineNumber has unsupported lineage '${columns[2]}'."
        }
        require(jvmBaseline != null && jvmBaseline in setOf(8, 17)) {
            "Publication inventory line $lineNumber has unsupported JVM baseline '${columns[3]}'."
        }
        ReleasePublicationInventoryEntry(artifactId, columns[1], columns[2], jvmBaseline)
    }
    require(entries.isNotEmpty() && entries.size == entries.map { entry -> entry.artifactId }.distinct().size) {
        "Publication inventory must contain a non-empty unique artifact ID set."
    }
    return entries
}

version = providers.gradleProperty("fileweftVersion").orNull
    ?: throw GradleException("-PfileweftVersion is required for release consumer smoke testing.")
val flowWeftRepositoryUrl = providers.gradleProperty("fileweftRepositoryUrl")
    .orElse(rootDir.resolve("../build/repository").toURI().toString())

val springBoot2Version = "2.7.18"
val springBoot3Version = "3.5.16"
val junitVersion = "5.11.4"
val kotlinVersion = "2.1.21"
val slf4jBoot2Version = "1.7.36"
val flywayBoot2Version = "8.5.13"
val flywayBoot3Version = "11.7.2"

val publicationInventory = readPublicationInventory(rootDir.resolve("../gradle/publication-inventory.tsv"))
val publishedJarInventory = publicationInventory.filter { entry -> entry.artifactKind == "jar" }
val expectedPublishedModules = publishedJarInventory.map { entry -> entry.artifactId }.toSet()
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
    expectedPublishedModules.forEach { moduleName ->
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
        add("implementation", "ai.icen:flowweft-retrieval-api:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-retrieval-spi:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-retrieval-runtime:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-agent-api:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-agent-runtime:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-api:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-spi:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-domain:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-runtime:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-workflow-persistence-jdbc:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-migration-cli:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-adapter-dify:${rootProject.version}")
        add("implementation", "ai.icen:flowweft-adapter-oss:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-agent:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-persistence:${rootProject.version}")
        add("implementation", "ai.icen:fileweft-adapter-micrometer:${rootProject.version}")
    }
}

val verifyReleaseInventory = tasks.register("verifyReleaseInventory") {
    group = "verification"
    description = "Resolves the exact physical publication inventory from the configured Maven repository."
    inputs.property("expectedPublishedModules", expectedPublishedModules.sorted())
    inputs.files(releaseInventory)

    doLast {
        val resolvedPublishedModules = releaseInventory.resolvedConfiguration.resolvedArtifacts
            .map { artifact -> artifact.moduleVersion.id }
            .filter { component -> component.group == "ai.icen" && component.version == project.version.toString() }
            .map { component -> component.name }
            .toSet()
        require(resolvedPublishedModules == expectedPublishedModules) {
            "Resolved publication inventory differs from the release contract; " +
                "missing=${expectedPublishedModules - resolvedPublishedModules}, " +
                "unexpected=${resolvedPublishedModules - expectedPublishedModules}."
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

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val mavenExecutable = providers.provider {
    val executableNames = if (isWindows) listOf("mvn.cmd", "mvn.bat", "mvn.exe") else listOf("mvn")
    val pathDirectories = System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .filter { path -> path.isNotBlank() }
        .map(::File)
    val mavenHomeDirectories = listOfNotNull(
        System.getenv("MAVEN_HOME"),
        System.getenv("M2_HOME"),
    ).map { home -> File(home, "bin") }
    (pathDirectories + mavenHomeDirectories)
        .asSequence()
        .flatMap { directory -> executableNames.asSequence().map(directory::resolve) }
        .firstOrNull { candidate -> candidate.isFile }
        ?: throw GradleException(
            "Maven CLI is required for the independent POM consumer gate. " +
                "Install the CI-pinned Maven distribution and expose mvn on PATH; " +
                "this release check never downloads a Maven runtime.",
        )
}
val mavenConsumerBuildDirectory = layout.buildDirectory.dir("maven-pom-consumer/target")
val mavenConsumerLocalRepository = layout.buildDirectory.dir("maven-pom-consumer/repository")
val verifyMavenPomConsumer = tasks.register<Exec>("verifyMavenPomConsumer") {
    group = "verification"
    description = "Test-compiles a Java 8 consumer with Maven CLI using only published FlowWeft POM metadata."
    workingDir(file("maven-consumer"))
    inputs.files(fileTree("maven-consumer") { include("pom.xml", "src/**/*.java") })
        .withPropertyName("mavenConsumerSources")
    inputs.property("fileweftVersion", project.version.toString())
    inputs.property("fileweftRepositoryUrl", flowWeftRepositoryUrl)

    doFirst {
        project.delete(mavenConsumerBuildDirectory, mavenConsumerLocalRepository)
        val arguments = listOf(
            "-B",
            "-ntp",
            "-U",
            "-Dstyle.color=never",
            "-Dmaven.repo.local=${mavenConsumerLocalRepository.get().asFile.absolutePath}",
            "-Dflowweft.version=${project.version}",
            "-Dflowweft.repository.url=${flowWeftRepositoryUrl.get()}",
            "-Dflowweft.maven.build.directory=${mavenConsumerBuildDirectory.get().asFile.absolutePath}",
            "test-compile",
        )
        val executable = mavenExecutable.get().absolutePath
        if (isWindows) {
            commandLine(listOf(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/c", executable) + arguments)
        } else {
            commandLine(listOf(executable) + arguments)
        }
        val existingMavenOptions = System.getenv("MAVEN_OPTS").orEmpty().trim()
        environment(
            "MAVEN_OPTS",
            listOf(existingMavenOptions, "-Dfile.encoding=UTF-8").filter(String::isNotEmpty).joinToString(" "),
        )
    }
    doLast {
        val compiledMainConsumer = mavenConsumerBuildDirectory.get().asFile
            .resolve("classes/ai/icen/fw/release/smoke/maven/MavenPomJava8Consumer.class")
        require(compiledMainConsumer.isFile && compiledMainConsumer.length() > 0L) {
            "Maven POM consumer did not produce the expected Java 8 main class: ${compiledMainConsumer.absolutePath}"
        }
        val compiledTestConsumer = mavenConsumerBuildDirectory.get().asFile
            .resolve("test-classes/ai/icen/fw/release/smoke/maven/MavenPublishedTestKitJava8Consumer.class")
        require(compiledTestConsumer.isFile && compiledTestConsumer.length() > 0L) {
            "Maven POM consumer did not produce the expected Java 8 test class: ${compiledTestConsumer.absolutePath}"
        }
    }
}

tasks.register("releaseSmoke") {
    group = "verification"
    description = "Verifies POM-only consumers, the release inventory, and Boot 2/3 hosts with host-owned JDBC."
    dependsOn(verifyReleaseInventory)
    dependsOn(verifyUnmanagedBoot3FlywayRuntime)
    dependsOn(verifyMavenPomConsumer)
    dependsOn(subprojects.map { consumerProject -> consumerProject.tasks.named("build") })
}
