package ai.icen.fw.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class Jvm8LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            withSourcesJar()
        }
        tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
            compilerOptions.javaParameters.set(true)
        }
        tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }

        val java8Test = registerJvmRuntimeTest(
            "java8Test", 8, "Runs this Java 8 baseline module's test suite on a Java 8 runtime.",
        )
        val compatibilityTests = listOf(
            java8Test,
            registerJvmRuntimeTest("java11Test", 11, "Runs this Java 8 baseline module's test suite on a Java 11 runtime."),
            registerJvmRuntimeTest("java21Test", 21, "Runs this Java 8 baseline module's test suite on a Java 21 runtime."),
            registerJvmRuntimeTest("java25Test", 25, "Runs this Java 8 baseline module's test suite on a Java 25 runtime."),
        )
        tasks.register("compatibilityTest") {
            group = "verification"
            description = "Runs this Java 8 baseline module across its supported Java runtimes."
            dependsOn(compatibilityTests)
        }
        tasks.named("check") {
            dependsOn(java8Test)
        }
        Unit
    }
}
