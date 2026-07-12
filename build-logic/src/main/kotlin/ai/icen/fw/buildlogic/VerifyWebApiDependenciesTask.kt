package ai.icen.fw.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Keeps `fileweft-web-api` a transport-neutral contract artifact.
 *
 * Source scanning alone cannot prove the absence of a runtime dependency: a
 * transitive or fully qualified framework type could otherwise be introduced
 * after the source guard. The plugin supplies only declared production
 * dependencies as task input, preserving configuration-cache compatibility.
 */
abstract class VerifyWebApiDependenciesTask : DefaultTask() {

    @get:Input
    abstract val declaredProductionDependencies: ListProperty<String>

    @TaskAction
    fun verify() {
        val forbidden = declaredProductionDependencies.get().filter(::isForbidden)
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "fileweft-web-api must remain a pure transport contract and cannot declare " +
                    "production dependencies on FileWeft runtime modules or HTTP frameworks:\n" +
                    forbidden.joinToString("\n") { dependency -> "  $dependency" },
            )
        }
    }

    private fun isForbidden(dependency: String): Boolean =
        dependency.startsWith("project:") ||
            dependency.startsWith("org.springframework:") ||
            dependency.startsWith("org.springframework.boot:") ||
            dependency.startsWith("javax.servlet:") ||
            dependency.startsWith("jakarta.servlet:") ||
            dependency.startsWith("javax.ws.rs:") ||
            dependency.startsWith("jakarta.ws.rs:")
}
