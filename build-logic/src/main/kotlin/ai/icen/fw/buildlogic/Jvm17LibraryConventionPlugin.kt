package ai.icen.fw.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class Jvm17LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            withSourcesJar()
        }
        tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            compilerOptions.javaParameters.set(true)
        }
        tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }
        val compatibilityTests = listOf(
            registerJvmRuntimeTest("java17Test", 17, "Runs this Java 17 module's test suite on a Java 17 runtime."),
            registerJvmRuntimeTest("java21Test", 21, "Runs this Java 17 module's test suite on a Java 21 runtime."),
            registerJvmRuntimeTest("java25Test", 25, "Runs this Java 17 module's test suite on a Java 25 runtime."),
        )
        tasks.register("compatibilityTest") {
            group = "verification"
            description = "Runs this Java 17 module across its supported Java runtimes."
            dependsOn(compatibilityTests)
        }
        Unit
    }
}
