import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Gradle 9 requires Java 17, while the repository can be built with newer JDKs.
// Keep convention plugins loadable by Gradle processes that do not run on JDK 25.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

gradlePlugin {
    plugins {
        register("fileweftJvm8Library") {
            id = "fileweft.jvm8-library"
            implementationClass = "ai.icen.fw.buildlogic.Jvm8LibraryConventionPlugin"
        }
        register("fileweftJvm17Library") {
            id = "fileweft.jvm17-library"
            implementationClass = "ai.icen.fw.buildlogic.Jvm17LibraryConventionPlugin"
        }
        register("fileweftArchitectureGuard") {
            id = "fileweft.architecture-guard"
            implementationClass = "ai.icen.fw.buildlogic.ArchitectureGuardPlugin"
        }
        register("fileweftSbomVerification") {
            id = "fileweft.sbom-verification"
            implementationClass = "ai.icen.fw.buildlogic.SbomVerificationPlugin"
        }
    }
}
