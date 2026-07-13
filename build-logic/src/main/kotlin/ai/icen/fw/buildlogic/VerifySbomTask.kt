package ai.icen.fw.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.nio.charset.StandardCharsets

/** Verifies the public release SBOM coordinates, module boundary, licenses, and format parity. */
abstract class VerifySbomTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jsonSbom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val xmlSbom: RegularFileProperty

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val expectedName: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val expectedModuleNames: ListProperty<String>

    @get:Input
    abstract val expectedLicenseName: Property<String>

    @get:Input
    abstract val expectedLicenseUrl: Property<String>

    @TaskAction
    fun verify() {
        listOf(jsonSbom.get().asFile, xmlSbom.get().asFile).forEach { file ->
            check(file.isFile && file.length() > 0L) { "Expected SBOM output was not generated: ${file.absolutePath}" }
        }
        val jsonModel = verifyJson()
        val xmlModel = verifyXml()
        check(jsonModel.componentCoordinates == xmlModel.componentCoordinates) {
            "Release JSON and XML SBOM component coordinates differ."
        }
        check(jsonModel.dependencies == xmlModel.dependencies) {
            "Release JSON and XML SBOM dependency graphs differ."
        }
    }

    private fun verifyJson(): SbomModel {
        val root = JsonSlurper().parse(jsonSbom.get().asFile, StandardCharsets.UTF_8.name()) as? Map<*, *>
            ?: error("Release JSON SBOM must contain a JSON object.")
        check(root["bomFormat"] == "CycloneDX") {
            "Release JSON SBOM does not declare the CycloneDX BOM format."
        }
        val metadata = root["metadata"] as? Map<*, *>
            ?: error("Release JSON SBOM does not contain metadata.")
        val rootComponent = metadata["component"] as? Map<*, *>
        verifyJsonRootComponent(rootComponent)
        verifyJsonLicense(rootComponent ?: emptyMap<Any, Any>(), "root")
        val components = (root["components"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
        val projectComponents = components.filter { component -> component["group"] == expectedGroup.get() }
        verifyExactModules(
            projectComponents.map { component -> component["name"] as? String },
            "Release JSON SBOM",
        )
        projectComponents.forEach { component ->
            check(component["version"] == expectedVersion.get()) {
                "Release JSON SBOM project component ${component["name"]} has the wrong version."
            }
            verifyJsonLicense(component, "module ${component["name"]}")
        }
        return SbomModel(
            componentCoordinates = components.map { component ->
                ComponentCoordinate(
                    component["group"] as? String,
                    component["name"] as? String,
                    component["version"] as? String,
                    component["bom-ref"] as? String,
                )
            },
            dependencies = (root["dependencies"] as? List<*>)
                .orEmpty()
                .filterIsInstance<Map<*, *>>()
                .associate { dependency ->
                    (dependency["ref"] as? String).orEmpty() to (dependency["dependsOn"] as? List<*>)
                        .orEmpty()
                        .filterIsInstance<String>()
                        .toSet()
                },
        )
    }

    private fun verifyJsonRootComponent(component: Map<*, *>?) {
        check(component != null) { "Release JSON SBOM does not contain the root component." }
        check(component["group"] == expectedGroup.get()) { "Release JSON SBOM has the wrong root component group." }
        check(component["name"] == expectedName.get()) { "Release JSON SBOM has the wrong root component name." }
        check(component["version"] == expectedVersion.get()) { "Release JSON SBOM has the wrong root component version." }
    }

    private fun verifyJsonLicense(component: Map<*, *>, description: String) {
        val licenses = (component["licenses"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { choice -> choice["license"] as? Map<*, *> }
        check(licenses.any { license ->
            (license["name"] == expectedLicenseName.get() || license["id"] == expectedLicenseName.get()) &&
                license["url"] == expectedLicenseUrl.get()
        }) {
            "Release JSON SBOM component $description does not declare ${expectedLicenseName.get()}."
        }
    }

    private fun verifyXml(): SbomModel {
        val document = GenerateReleaseSbomTask.secureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(xmlSbom.get().asFile)
        val root = document.documentElement
        check(root.localName == "bom" && root.namespaceURI.orEmpty().contains("cyclonedx")) {
            "Release XML SBOM is not a namespaced CycloneDX BOM."
        }
        val metadata = directChild(root, "metadata")
            ?: error("Release XML SBOM does not contain metadata.")
        val rootComponent = directChild(metadata, "component")
        verifyXmlRootComponent(rootComponent)
        verifyXmlLicense(rootComponent ?: error("Release XML SBOM does not contain the root component."), "root")
        val components = directChild(root, "components")
            ?: error("Release XML SBOM does not contain components.")
        val componentElements = directChildren(components, "component")
        val projectComponents = componentElements
            .filter { component -> directChildText(component, "group") == expectedGroup.get() }
        verifyExactModules(
            projectComponents.map { component -> directChildText(component, "name") },
            "Release XML SBOM",
        )
        projectComponents.forEach { component ->
            check(directChildText(component, "version") == expectedVersion.get()) {
                "Release XML SBOM project component ${directChildText(component, "name")} has the wrong version."
            }
            verifyXmlLicense(component, "module ${directChildText(component, "name")}")
        }
        val dependencies = directChild(root, "dependencies")
        return SbomModel(
            componentCoordinates = componentElements.map { component ->
                ComponentCoordinate(
                    directChildText(component, "group"),
                    directChildText(component, "name"),
                    directChildText(component, "version"),
                    component.getAttribute("bom-ref").ifBlank { null },
                )
            },
            dependencies = dependencies?.let { container ->
                directChildren(container, "dependency").associate { dependency ->
                    dependency.getAttribute("ref") to directChildren(dependency, "dependency")
                        .map { child -> child.getAttribute("ref") }
                        .toSet()
                }
            }.orEmpty(),
        )
    }

    private fun verifyXmlRootComponent(component: Element?) {
        check(component != null) { "Release XML SBOM does not contain the root component." }
        check(directChildText(component, "group") == expectedGroup.get()) {
            "Release XML SBOM has the wrong root component group."
        }
        check(directChildText(component, "name") == expectedName.get()) {
            "Release XML SBOM has the wrong root component name."
        }
        check(directChildText(component, "version") == expectedVersion.get()) {
            "Release XML SBOM has the wrong root component version."
        }
    }

    private fun verifyXmlLicense(container: Element, description: String) {
        val licenses = directChild(container, "licenses")
            ?.let { container -> directChildren(container, "license") }
            .orEmpty()
        check(licenses.any { license ->
            (directChildText(license, "name") == expectedLicenseName.get() ||
                directChildText(license, "id") == expectedLicenseName.get()) &&
                directChildText(license, "url") == expectedLicenseUrl.get()
        }) {
            "Release XML SBOM component $description does not declare ${expectedLicenseName.get()}."
        }
    }

    private fun verifyExactModules(actualNames: List<String?>, format: String) {
        val expected = expectedModuleNames.get().toSet()
        check(expected.isNotEmpty()) { "The release SBOM expected module set must not be empty." }
        check(expected.size == expectedModuleNames.get().size) {
            "The release SBOM expected module set contains duplicates."
        }
        val nonNullNames = actualNames.filterNotNull()
        val duplicates = nonNullNames.groupingBy { name -> name }.eachCount().filterValues { count -> count > 1 }.keys
        check(duplicates.isEmpty()) { "$format contains duplicate ${expectedGroup.get()} modules: $duplicates." }
        val actual = nonNullNames.toSet()
        check(actual == expected) {
            "$format ${expectedGroup.get()} modules differ from the reviewed public set; " +
                "missing=${expected - actual}, unexpected=${actual - expected}."
        }
    }

    private fun directChild(parent: Element, localName: String): Element? =
        directChildren(parent, localName).firstOrNull()

    private fun directChildText(parent: Element, localName: String): String? =
        directChild(parent, localName)?.textContent?.trim()

    private fun directChildren(parent: Element, localName: String): List<Element> =
        GenerateReleaseSbomTask.directChildren(parent, localName)

    private data class ComponentCoordinate(
        val group: String?,
        val name: String?,
        val version: String?,
        val reference: String?,
    )

    private data class SbomModel(
        val componentCoordinates: List<ComponentCoordinate>,
        val dependencies: Map<String, Set<String>>,
    )
}
