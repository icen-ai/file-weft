package com.fileweft.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets

/**
 * Source-level dependency guard for FileWeft's inward-only module boundaries.
 *
 * Import scanning makes an architectural violation actionable before compilation;
 * Gradle project dependencies remain the authoritative dependency graph check.
 */
abstract class VerifyFileWeftArchitectureTask : DefaultTask() {

    @get:Input
    abstract val approvedImportPrefixesByModule: MapProperty<String, List<String>>

    @get:Input
    abstract val forbiddenInfrastructurePrefixes: org.gradle.api.provider.ListProperty<String>

    @get:Input
    abstract val forbiddenKotlinSyntaxByModule: MapProperty<String, List<String>>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoots: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val approvedPrefixesByModule = approvedImportPrefixesByModule.get()
        val forbiddenPrefixes = forbiddenInfrastructurePrefixes.get()
        val forbiddenSyntaxByModule = forbiddenKotlinSyntaxByModule.get()
        val violations = mutableListOf<String>()

        sourceRoots.files
            .filter { sourceRoot -> sourceRoot.isDirectory }
            .sortedBy { sourceRoot -> sourceRoot.invariantSeparatorsPath }
            .forEach { sourceRoot ->
                val module = sourceRoot.parentFile?.parentFile?.parentFile?.name ?: return@forEach
                val approvedPrefixes = approvedPrefixesByModule[module] ?: return@forEach
                val forbiddenSyntax = forbiddenSyntaxByModule[module].orEmpty()

                sourceRoot.walkTopDown()
                    .filter { source -> source.isFile && source.extension == "kt" }
                    .toList()
                    .sortedBy { source -> source.invariantSeparatorsPath }
                    .forEach { source ->
                        source.readLines(StandardCharsets.UTF_8).forEachIndexed { index, line ->
                            val importedType = line.trim()
                                .takeIf { it.startsWith("import ") }
                                ?.removePrefix("import ")
                                ?.substringBefore(" as ")
                            if (importedType != null) {
                                val forbiddenPrefix = forbiddenPrefixes.firstOrNull(importedType::startsWith)
                                val approved = approvedPrefixes.any(importedType::startsWith)
                                if (forbiddenPrefix != null || !approved) {
                                    val relativePath = source.relativeTo(sourceRoot).invariantSeparatorsPath
                                    val reason = forbiddenPrefix?.let { prefix -> "forbidden prefix: $prefix" }
                                        ?: "not approved for $module"
                                    violations += "$module/$relativePath:${index + 1} imports $importedType ($reason)"
                                }
                            }

                            forbiddenSyntax.firstOrNull { syntax -> line.contains(syntax) }?.let { syntax ->
                                val relativePath = source.relativeTo(sourceRoot).invariantSeparatorsPath
                                violations += "$module/$relativePath:${index + 1} uses forbidden Kotlin API syntax: $syntax"
                            }
                        }
                    }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "FileWeft architecture boundary violations found:\n${violations.joinToString("\n")}" +
                    "\nUse an SPI, Java-friendly API type, or move the infrastructure concern to the appropriate outer module.",
            )
        }
    }
}
