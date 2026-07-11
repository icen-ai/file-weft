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

    /**
     * Explicit infrastructure exceptions owned by an outer module.  A value is only effective when it
     * narrows [forbiddenInfrastructurePrefixes]; it never relaxes another module's boundary.
     */
    @get:Input
    abstract val allowedInfrastructurePrefixesByModule: MapProperty<String, List<String>>

    /**
     * Compatibility ordering strings accepted only in an exact [AutoConfiguration] [afterName] declaration.
     * This deliberately does not permit imports, type references, or arbitrary string references to starter code.
     */
    @get:Input
    abstract val allowedAutoConfigurationAfterNameReferencesByModule: MapProperty<String, List<String>>

    @get:Input
    abstract val forbiddenKotlinSyntaxByModule: MapProperty<String, List<String>>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoots: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val approvedPrefixesByModule = approvedImportPrefixesByModule.get()
        val forbiddenPrefixes = forbiddenInfrastructurePrefixes.get()
        val allowedInfrastructureByModule = allowedInfrastructurePrefixesByModule.get()
        val allowedAutoConfigurationAfterNameReferences = allowedAutoConfigurationAfterNameReferencesByModule.get()
        val forbiddenSyntaxByModule = forbiddenKotlinSyntaxByModule.get()
        val violations = mutableListOf<String>()

        sourceRoots.files
            .filter { sourceRoot -> sourceRoot.isDirectory }
            .sortedBy { sourceRoot -> sourceRoot.invariantSeparatorsPath }
            .forEach { sourceRoot ->
                val module = sourceRoot.parentFile?.parentFile?.parentFile?.name ?: return@forEach
                val approvedPrefixes = approvedPrefixesByModule[module] ?: return@forEach
                val allowedInfrastructurePrefixes = allowedInfrastructureByModule[module].orEmpty()
                val allowedAutoConfigurationAfterNameReferences =
                    allowedAutoConfigurationAfterNameReferences[module].orEmpty()
                val forbiddenSyntax = forbiddenSyntaxByModule[module].orEmpty()

                sourceRoot.walkTopDown()
                    .filter { source -> source.isFile && source.extension in SOURCE_EXTENSIONS }
                    .toList()
                    .sortedBy { source -> source.invariantSeparatorsPath }
                    .forEach { source ->
                        source.readLines(StandardCharsets.UTF_8).forEachIndexed { index, line ->
                            val trimmedLine = line.trim()
                            val importedType = trimmedLine
                                .takeIf { it.startsWith("import ") }
                                ?.removePrefix("import ")
                                ?.removePrefix("static ")
                                ?.substringBefore(" as ")
                            if (importedType != null) {
                                val forbiddenPrefix = forbiddenPrefixes.firstOrNull { prefix ->
                                    importedType.startsWith(prefix) && !allowedInfrastructurePrefixes.allows(prefix, importedType)
                                }
                                val approved = approvedPrefixes.any(importedType::startsWith)
                                if (forbiddenPrefix != null || !approved) {
                                    val relativePath = source.relativeTo(sourceRoot).invariantSeparatorsPath
                                    val reason = forbiddenPrefix?.let { prefix -> "forbidden prefix: $prefix" }
                                        ?: "not approved for $module"
                                    violations += "$module/$relativePath:${index + 1} imports $importedType ($reason)"
                                }
                            } else {
                                val relativePath = source.relativeTo(sourceRoot).invariantSeparatorsPath
                                forbiddenPrefixes.firstOrNull { prefix ->
                                    line.contains(prefix) && !allowedInfrastructurePrefixes.allows(prefix, line)
                                }?.let { prefix ->
                                    violations += "$module/$relativePath:${index + 1} references $prefix (forbidden prefix: $prefix)"
                                }
                                FILEWEFT_TYPE_REFERENCE.findAll(line)
                                    .map { match -> match.value }
                                    .distinct()
                                    .filterNot { reference ->
                                        approvedPrefixes.any { prefix ->
                                            reference == prefix.removeSuffix(".") || reference.startsWith(prefix)
                                        } || allowedAutoConfigurationAfterNameReferences
                                            .allowsAutoConfigurationAfterName(line, reference)
                                    }
                                    .forEach { reference ->
                                        violations += "$module/$relativePath:${index + 1} references $reference (not approved for $module)"
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

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val FILEWEFT_TYPE_REFERENCE = Regex("\\bcom\\.fileweft\\.[A-Za-z0-9_.]+")
        val AUTO_CONFIGURATION_AFTER_NAME = Regex(
            """^@AutoConfiguration\s*\(\s*afterName\s*=\s*\[\s*"([^"]+)"\s*]\s*\)\s*$""",
        )
    }

    private fun List<String>.allows(forbiddenPrefix: String, reference: String): Boolean = any { allowedPrefix ->
        allowedPrefix.startsWith(forbiddenPrefix) && reference.contains(allowedPrefix)
    }

    private fun List<String>.allowsAutoConfigurationAfterName(line: String, reference: String): Boolean {
        val declaredReference = AUTO_CONFIGURATION_AFTER_NAME.matchEntire(line.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?: return false
        return declaredReference == reference && any { allowedReference -> allowedReference == reference }
    }
}
