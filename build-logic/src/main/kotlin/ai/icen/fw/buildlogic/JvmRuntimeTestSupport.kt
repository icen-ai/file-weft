package ai.icen.fw.buildlogic

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/** Registers the normal test source set against one explicit Java runtime. */
internal fun Project.registerJvmRuntimeTest(
    taskName: String,
    runtimeVersion: Int,
    description: String,
): TaskProvider<Test> {
    val launchers = extensions.getByType(JavaToolchainService::class.java)
    val launcher = launchers.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(runtimeVersion))
    }
    val testSourceSet = extensions.getByType(SourceSetContainer::class.java).getByName("test")
    return tasks.register(taskName, Test::class.java) {
        group = "verification"
        this.description = description
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        javaLauncher.set(launcher)
        useJUnitPlatform()
        shouldRunAfter(tasks.named("test"))
    }
}
