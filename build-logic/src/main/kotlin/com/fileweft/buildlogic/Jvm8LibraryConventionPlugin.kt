package com.fileweft.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
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

        val java8Launcher = extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
        val testSourceSet = extensions.getByType(SourceSetContainer::class.java).getByName("test")
        val java8Test = tasks.register("java8Test", Test::class.java) {
            group = "verification"
            description = "Runs this Java 8 baseline module's test suite on a Java 8 runtime."
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            javaLauncher.set(java8Launcher)
            useJUnitPlatform()
            shouldRunAfter(tasks.named("test"))
        }
        tasks.named("check") {
            dependsOn(java8Test)
        }
        Unit
    }
}
