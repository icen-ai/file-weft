package ai.icen.fw.buildlogic

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

internal const val EXTERNAL_INTEGRATION_TEST_PATTERN = "**/*IntegrationTest.class"

private const val H2_GROUP = "com.h2database"
private const val H2_MODULE = "h2"
private const val JAVA_8_COMPATIBLE_H2_VERSION = "1.4.200"
private val H2_JAR_NAME = Regex("^h2-[0-9].*\\.jar$")

/** Keeps external-system suites out of ordinary and cross-JDK unit-test tasks. */
internal fun Test.excludeExternalIntegrationTests() {
    exclude(EXTERNAL_INTEGRATION_TEST_PATTERN)
}

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
    val runtimeClasspath = if (runtimeVersion == 8) {
        val compatibleH2 = configurations.detachedConfiguration(
            dependencies.create("$H2_GROUP:$H2_MODULE:$JAVA_8_COMPATIBLE_H2_VERSION"),
        )
        testSourceSet.runtimeClasspath.filter { file -> !H2_JAR_NAME.matches(file.name) } + compatibleH2
    } else {
        testSourceSet.runtimeClasspath
    }
    return tasks.register(taskName, Test::class.java) {
        group = "verification"
        this.description = description
        testClassesDirs = testSourceSet.output.classesDirs
        // H2 2.x requires Java 11. Keep it for ordinary and newer-runtime tests, but replace
        // any H2 dependency with the final Java 8-compatible release on the Java 8 lane.
        classpath = runtimeClasspath
        javaLauncher.set(launcher)
        useJUnitPlatform()
        excludeExternalIntegrationTests()
        shouldRunAfter(tasks.named("test"))
    }
}
