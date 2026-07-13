package ai.icen.fw.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Derives deterministic public-release SBOMs from the CycloneDX plugin's aggregate outputs.
 *
 * Third-party components are retained. Internal components are restricted to the reviewed
 * publishable module set, and the root plus every published module receives the project license.
 */
@CacheableTask
abstract class GenerateReleaseSbomTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aggregateJsonSbom: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aggregateXmlSbom: RegularFileProperty

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val expectedName: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val publishableModuleNames: ListProperty<String>

    @get:Input
    abstract val licenseName: Property<String>

    @get:Input
    abstract val licenseUrl: Property<String>

    @get:OutputFile
    abstract val releaseJsonSbom: RegularFileProperty

    @get:OutputFile
    abstract val releaseXmlSbom: RegularFileProperty

    @TaskAction
    fun generate() {
        val modules = reviewedModules()
        generateJson(modules)
        generateXml(modules)
    }

    private fun reviewedModules(): Set<String> {
        val configured = publishableModuleNames.get()
        check(configured.isNotEmpty()) { "The release SBOM requires at least one publishable module." }
        check(configured.none { module -> module.isBlank() }) {
            "The release SBOM contains a blank publishable module name."
        }
        check(configured.size == configured.toSet().size) {
            "The release SBOM contains duplicate publishable module names."
        }
        return configured.toSortedSet()
    }

    private fun generateJson(modules: Set<String>) {
        val parsed = JsonSlurper().parse(
            aggregateJsonSbom.get().asFile,
            StandardCharsets.UTF_8.name(),
        ) as? Map<*, *> ?: error("Aggregate JSON SBOM must contain a JSON object.")
        val root = parsed.toMutableJsonObject()
        check(root["bomFormat"] == "CycloneDX") {
            "Aggregate JSON SBOM does not declare the CycloneDX BOM format."
        }
        root.remove("serialNumber")

        val metadata = root.mutableObject("metadata", "Aggregate JSON SBOM does not contain metadata.")
        metadata.remove("timestamp")
        val rootComponent = metadata.mutableObject(
            "component",
            "Aggregate JSON SBOM does not contain the root component.",
        )
        verifyCoordinates(
            rootComponent["group"] as? String,
            rootComponent["name"] as? String,
            rootComponent["version"] as? String,
            "Aggregate JSON SBOM root component",
            expectedName.get(),
        )
        rootComponent["licenses"] = jsonLicenseChoice()
        metadata.remove("licenses")

        val components = (root["components"] as? List<*>)
            ?.mapNotNull { component -> (component as? Map<*, *>)?.toMutableJsonObject() }
            ?: error("Aggregate JSON SBOM does not contain components.")
        validateJsonComponents(components, "Aggregate JSON SBOM")
        val internalComponents = components.filter { component -> component["group"] == expectedGroup.get() }
        verifyReviewedComponents(
            internalComponents.map { component ->
                InternalComponent(
                    name = component["name"] as? String,
                    version = component["version"] as? String,
                    reference = component["bom-ref"] as? String,
                )
            },
            modules,
            "Aggregate JSON SBOM",
        )

        val retainedComponents = components.filter { component ->
            component["group"] != expectedGroup.get() || component["name"] in modules
        }
        retainedComponents
            .filter { component -> component["group"] == expectedGroup.get() }
            .forEach { component -> component["licenses"] = jsonLicenseChoice() }
        val removedReferences = internalComponents
            .filter { component -> component["name"] !in modules }
            .mapNotNull { component -> component["bom-ref"] as? String }
            .toSet()
        val rootReference = rootComponent["bom-ref"] as? String
            ?: error("Aggregate JSON SBOM root component does not declare a bom-ref.")
        val reviewedReferences = retainedComponents
            .filter { component -> component["group"] == expectedGroup.get() }
            .map { component -> component["bom-ref"] as String }
            .toSet()
        val pruned = pruneJsonGraph(
            rootReference,
            retainedComponents,
            ensureJsonRootDependency(
                rootReference,
                reviewedReferences,
                filterJsonDependencies(root["dependencies"], removedReferences),
            ),
        )
        root["components"] = pruned.components
        root["dependencies"] = pruned.dependencies

        val output = releaseJsonSbom.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun filterJsonDependencies(value: Any?, removedReferences: Set<String>): List<MutableMap<String, Any?>> {
        return (value as? List<*>)
            .orEmpty()
            .mapNotNull { dependency -> (dependency as? Map<*, *>)?.toMutableJsonObject() }
            .filterNot { dependency -> dependency["ref"] in removedReferences }
            .onEach { dependency ->
                dependency["dependsOn"] = (dependency["dependsOn"] as? List<*>)
                    .orEmpty()
                    .filterIsInstance<String>()
                    .filterNot { reference -> reference in removedReferences }
            }
    }

    private fun generateXml(modules: Set<String>) {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(aggregateXmlSbom.get().asFile)
        val bom = document.documentElement
        check(bom.localName == "bom" && bom.namespaceURI.orEmpty().contains("cyclonedx")) {
            "Aggregate XML SBOM is not a namespaced CycloneDX BOM."
        }
        bom.removeAttribute("serialNumber")
        val metadata = directChild(bom, "metadata")
            ?: error("Aggregate XML SBOM does not contain metadata.")
        directChildren(metadata, "timestamp").forEach { timestamp -> metadata.removeChild(timestamp) }
        val rootComponent = directChild(metadata, "component")
            ?: error("Aggregate XML SBOM does not contain the root component.")
        verifyCoordinates(
            directChildText(rootComponent, "group"),
            directChildText(rootComponent, "name"),
            directChildText(rootComponent, "version"),
            "Aggregate XML SBOM root component",
            expectedName.get(),
        )
        replaceXmlLicense(document, rootComponent)
        directChildren(metadata, "licenses").forEach { child -> metadata.removeChild(child) }

        val componentsContainer = directChild(bom, "components")
            ?: error("Aggregate XML SBOM does not contain components.")
        val componentElements = directChildren(componentsContainer, "component")
        validateXmlComponents(componentElements, "Aggregate XML SBOM")
        val internalComponents = componentElements
            .filter { component -> directChildText(component, "group") == expectedGroup.get() }
        verifyReviewedComponents(
            internalComponents.map { component ->
                InternalComponent(
                    name = directChildText(component, "name"),
                    version = directChildText(component, "version"),
                    reference = component.getAttribute("bom-ref").ifBlank { null },
                )
            },
            modules,
            "Aggregate XML SBOM",
        )

        val removedReferences = mutableSetOf<String>()
        internalComponents.forEach { component ->
            if (directChildText(component, "name") in modules) {
                replaceXmlLicense(document, component)
            } else {
                component.getAttribute("bom-ref").takeIf { reference -> reference.isNotBlank() }
                    ?.let(removedReferences::add)
                componentsContainer.removeChild(component)
            }
        }
        filterXmlDependencies(bom, removedReferences)
        val rootReference = rootComponent.getAttribute("bom-ref").takeIf { reference -> reference.isNotBlank() }
            ?: error("Aggregate XML SBOM root component does not declare a bom-ref.")
        val reviewedReferences = directChildren(componentsContainer, "component")
            .filter { component -> directChildText(component, "group") == expectedGroup.get() }
            .map { component -> component.getAttribute("bom-ref") }
            .toSet()
        ensureXmlRootDependency(document, bom, rootReference, reviewedReferences)
        pruneXmlGraph(bom, componentsContainer, rootReference)
        writeXml(document)
    }

    private fun ensureJsonRootDependency(
        rootReference: String,
        reviewedReferences: Set<String>,
        dependencies: List<MutableMap<String, Any?>>,
    ): List<MutableMap<String, Any?>> {
        val rootDependency = dependencies.firstOrNull { dependency -> dependency["ref"] == rootReference }
            ?: linkedMapOf<String, Any?>("ref" to rootReference, "dependsOn" to emptyList<String>())
        val existingTargets = (rootDependency["dependsOn"] as? List<*>)
            .orEmpty()
            .filterIsInstance<String>()
        rootDependency["dependsOn"] = (existingTargets + reviewedReferences).distinct().sorted()
        return if (rootDependency in dependencies) dependencies else listOf(rootDependency) + dependencies
    }

    private fun ensureXmlRootDependency(
        document: Document,
        bom: Element,
        rootReference: String,
        reviewedReferences: Set<String>,
    ) {
        val dependencies = directChild(bom, "dependencies")
            ?: document.createElementNS(bom.namespaceURI, "dependencies").also(bom::appendChild)
        val rootDependency = directChildren(dependencies, "dependency")
            .firstOrNull { dependency -> dependency.getAttribute("ref") == rootReference }
            ?: document.createElementNS(bom.namespaceURI, "dependency").also { dependency ->
                dependency.setAttribute("ref", rootReference)
                dependencies.insertBefore(dependency, dependencies.firstChild)
            }
        val existingTargets = directChildren(rootDependency, "dependency")
            .map { dependency -> dependency.getAttribute("ref") }
            .toSet()
        (reviewedReferences - existingTargets).sorted().forEach { reference ->
            rootDependency.appendChild(
                document.createElementNS(bom.namespaceURI, "dependency").apply {
                    setAttribute("ref", reference)
                },
            )
        }
    }

    private fun pruneJsonGraph(
        rootReference: String,
        components: List<MutableMap<String, Any?>>,
        dependencies: List<MutableMap<String, Any?>>,
    ): JsonGraph {
        val componentReferences = components.map { component ->
            component["bom-ref"] as? String
                ?: error("Aggregate JSON SBOM component ${component["name"]} does not declare a bom-ref.")
        }
        check(componentReferences.size == componentReferences.toSet().size) {
            "Aggregate JSON SBOM contains duplicate component bom-ref values."
        }
        val graph = dependencyGraph(
            dependencies.map { dependency ->
                val reference = dependency["ref"] as? String
                    ?: error("Aggregate JSON SBOM dependency does not declare a ref.")
                reference to (dependency["dependsOn"] as? List<*>)
                    .orEmpty()
                    .map { target -> target as? String ?: error("Aggregate JSON SBOM dependency target is not a string.") }
                    .toSet()
            },
            rootReference,
            componentReferences.toSet(),
            "Aggregate JSON SBOM",
        )
        val reachable = reachableReferences(rootReference, graph)
        return JsonGraph(
            components = components.filter { component -> component["bom-ref"] in reachable },
            dependencies = dependencies.filter { dependency -> dependency["ref"] in reachable },
        )
    }

    private fun pruneXmlGraph(bom: Element, components: Element, rootReference: String) {
        val componentElements = directChildren(components, "component")
        val componentReferences = componentElements.map { component ->
            component.getAttribute("bom-ref").takeIf { reference -> reference.isNotBlank() }
                ?: error("Aggregate XML SBOM component ${directChildText(component, "name")} does not declare a bom-ref.")
        }
        check(componentReferences.size == componentReferences.toSet().size) {
            "Aggregate XML SBOM contains duplicate component bom-ref values."
        }
        val dependencies = directChild(bom, "dependencies")
            ?: error("Aggregate XML SBOM does not contain dependencies.")
        val dependencyElements = directChildren(dependencies, "dependency")
        val graph = dependencyGraph(
            dependencyElements.map { dependency ->
                val reference = dependency.getAttribute("ref").takeIf { value -> value.isNotBlank() }
                    ?: error("Aggregate XML SBOM dependency does not declare a ref.")
                reference to directChildren(dependency, "dependency")
                    .map { child ->
                        child.getAttribute("ref").takeIf { value -> value.isNotBlank() }
                            ?: error("Aggregate XML SBOM dependency target does not declare a ref.")
                    }
                    .toSet()
            },
            rootReference,
            componentReferences.toSet(),
            "Aggregate XML SBOM",
        )
        val reachable = reachableReferences(rootReference, graph)
        componentElements
            .filter { component -> component.getAttribute("bom-ref") !in reachable }
            .forEach { component -> components.removeChild(component) }
        dependencyElements
            .filter { dependency -> dependency.getAttribute("ref") !in reachable }
            .forEach { dependency -> dependencies.removeChild(dependency) }
    }

    private fun dependencyGraph(
        entries: List<Pair<String, Set<String>>>,
        rootReference: String,
        componentReferences: Set<String>,
        source: String,
    ): Map<String, Set<String>> {
        val duplicateReferences = entries.groupingBy { entry -> entry.first }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        check(duplicateReferences.isEmpty()) { "$source contains duplicate dependency refs: $duplicateReferences." }
        val graph = entries.toMap()
        val knownReferences = componentReferences + rootReference
        val unknownSources = graph.keys - knownReferences
        val unknownTargets = graph.values.flatten().toSet() - knownReferences
        check(unknownSources.isEmpty() && unknownTargets.isEmpty()) {
            "$source dependency graph contains unknown refs; sources=$unknownSources, targets=$unknownTargets."
        }
        check(rootReference in graph) { "$source dependency graph does not contain the root component." }
        return graph
    }

    private fun reachableReferences(rootReference: String, graph: Map<String, Set<String>>): Set<String> {
        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.add(rootReference)
        while (pending.isNotEmpty()) {
            val reference = pending.removeFirst()
            if (reachable.add(reference)) {
                graph[reference].orEmpty().forEach(pending::addLast)
            }
        }
        return reachable
    }

    private fun replaceXmlLicense(document: Document, component: Element) {
        directChildren(component, "licenses").forEach { child -> component.removeChild(child) }
        val namespace = component.namespaceURI
        val licenses = document.createElementNS(namespace, "licenses")
        val license = document.createElementNS(namespace, "license")
        val name = document.createElementNS(namespace, "name").apply { textContent = licenseName.get() }
        val url = document.createElementNS(namespace, "url").apply { textContent = licenseUrl.get() }
        license.appendChild(name)
        license.appendChild(url)
        licenses.appendChild(license)

        val followingElementNames = setOf(
            "copyright",
            "cpe",
            "purl",
            "omniborId",
            "swhid",
            "swid",
            "modified",
            "pedigree",
            "externalReferences",
            "properties",
            "components",
            "evidence",
            "releaseNotes",
            "modelCard",
            "data",
        )
        val insertionPoint = childElements(component)
            .firstOrNull { child -> child.localName in followingElementNames }
        component.insertBefore(licenses, insertionPoint)
    }

    private fun filterXmlDependencies(bom: Element, removedReferences: Set<String>) {
        val dependencies = directChild(bom, "dependencies") ?: return
        directChildren(dependencies, "dependency").forEach { dependency ->
            if (dependency.getAttribute("ref") in removedReferences) {
                dependencies.removeChild(dependency)
            } else {
                directChildren(dependency, "dependency")
                    .filter { child -> child.getAttribute("ref") in removedReferences }
                    .forEach { child -> dependency.removeChild(child) }
            }
        }
    }

    private fun writeXml(document: Document) {
        val factory = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name())
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        val output = releaseXmlSbom.get().asFile
        output.parentFile.mkdirs()
        output.outputStream().buffered().use { stream ->
            transformer.transform(DOMSource(document), StreamResult(stream))
        }
    }

    private fun validateJsonComponents(components: List<MutableMap<String, Any?>>, source: String) {
        check(components.isNotEmpty()) { "$source does not contain any components." }
        val references = mutableSetOf<String>()
        components.forEach { component ->
            val reference = component["bom-ref"] as? String
            check(!reference.isNullOrBlank()) { "$source component ${component["name"]} does not declare a bom-ref." }
            check(references.add(reference)) { "$source contains duplicate component bom-ref: $reference." }
            validateComponentIdentity(
                group = component["group"] as? String,
                name = component["name"] as? String,
                version = component["version"] as? String,
                source = "$source component $reference",
            )
        }
    }

    private fun validateXmlComponents(components: List<Element>, source: String) {
        check(components.isNotEmpty()) { "$source does not contain any components." }
        val references = mutableSetOf<String>()
        components.forEach { component ->
            val reference = component.getAttribute("bom-ref").takeIf { it.isNotBlank() }
                ?: error("$source component ${directChildText(component, "name")} does not declare a bom-ref.")
            check(references.add(reference)) { "$source contains duplicate component bom-ref: $reference." }
            validateComponentIdentity(
                group = directChildText(component, "group"),
                name = directChildText(component, "name"),
                version = directChildText(component, "version"),
                source = "$source component $reference",
            )
        }
    }

    private fun validateComponentIdentity(group: String?, name: String?, version: String?, source: String) {
        check(!group.isNullOrBlank()) { "$source has a blank group." }
        check(!name.isNullOrBlank()) { "$source has a blank name." }
        check(!version.isNullOrBlank()) { "$source has a blank version." }
        check(!name.contains("..") && !name.startsWith("/") && !name.startsWith("\\")) {
            "$source has a path-traversal-like name: $name."
        }
        if (!expectedVersion.get().endsWith("-SNAPSHOT")) {
            check(!version.contains("-SNAPSHOT")) { "$source contains a SNAPSHOT version in a non-SNAPSHOT release: $version." }
        }
    }

    private fun verifyReviewedComponents(
        components: List<InternalComponent>,
        modules: Set<String>,
        source: String,
    ) {
        val namedComponents = components.filter { component -> component.name != null }
        val names = namedComponents.mapNotNull { component -> component.name }
        val duplicates = names.groupingBy { name -> name }.eachCount().filterValues { count -> count > 1 }.keys
        check(duplicates.isEmpty()) { "$source contains duplicate internal components: $duplicates." }
        val actual = names.toSet()
        check(actual.containsAll(modules)) {
            "$source is missing publishable modules: ${modules - actual}."
        }
        namedComponents.filter { component -> component.name in modules }.forEach { component ->
            check(component.version == expectedVersion.get()) {
                "$source component ${component.name} has version ${component.version}; expected ${expectedVersion.get()}."
            }
            check(!component.reference.isNullOrBlank()) {
                "$source component ${component.name} does not declare a bom-ref."
            }
        }
    }

    private fun verifyCoordinates(
        group: String?,
        name: String?,
        version: String?,
        description: String,
        requiredName: String,
    ) {
        check(group == expectedGroup.get()) { "$description has group $group; expected ${expectedGroup.get()}." }
        check(name == requiredName) { "$description has name $name; expected $requiredName." }
        check(version == expectedVersion.get()) {
            "$description has version $version; expected ${expectedVersion.get()}."
        }
    }

    private fun jsonLicenseChoice(): List<Map<String, Map<String, String>>> = listOf(
        mapOf(
            "license" to linkedMapOf(
                "name" to licenseName.get(),
                "url" to licenseUrl.get(),
            ),
        ),
    )

    private fun Map<*, *>.toMutableJsonObject(): MutableMap<String, Any?> = entries.associateTo(linkedMapOf()) {
        (key, value) -> key.toString() to value.toMutableJsonValue()
    }

    private fun Any?.toMutableJsonValue(): Any? = when (this) {
        is Map<*, *> -> toMutableJsonObject()
        is List<*> -> map { value -> value.toMutableJsonValue() }.toMutableList()
        else -> this
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.mutableObject(key: String, message: String): MutableMap<String, Any?> =
        this[key] as? MutableMap<String, Any?> ?: error(message)

    private data class InternalComponent(
        val name: String?,
        val version: String?,
        val reference: String?,
    )

    private data class JsonGraph(
        val components: List<MutableMap<String, Any?>>,
        val dependencies: List<MutableMap<String, Any?>>,
    )

    companion object {
        internal fun secureDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

        internal fun directChild(parent: Element, localName: String): Element? =
            directChildren(parent, localName).firstOrNull()

        internal fun directChildText(parent: Element, localName: String): String? =
            directChild(parent, localName)?.textContent?.trim()

        internal fun directChildren(parent: Element, localName: String): List<Element> =
            childElements(parent).filter { child -> child.localName == localName || child.nodeName == localName }

        internal fun childElements(parent: Element): List<Element> =
            (0 until parent.childNodes.length)
                .asSequence()
                .map { index -> parent.childNodes.item(index) }
                .filterIsInstance<Element>()
                .toList()
    }
}
