package ai.icen.fw.testkit.metadata

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import java.util.Collections
import java.util.LinkedHashMap

class MetadataSchemaResolverContractTestBehaviorTest : MetadataSchemaResolverContractTest() {
    override val metadataSchemaResolver: MetadataSchemaResolver = object : MetadataSchemaResolver {
        override fun resolve(context: MetadataSchemaContext): MetadataSchema? {
            return if (
                context.tenantId == "tenant-contract" &&
                context.schemaId == "document-contract" &&
                context.schemaVersion == "v1"
            ) {
                schema()
            } else {
                null
            }
        }
    }

    override fun knownContext(): MetadataSchemaContext = context("tenant-contract")

    override fun absentContext(): MetadataSchemaContext = context("tenant-other")

    private fun context(tenantId: String): MetadataSchemaContext = MetadataSchemaContext(
        tenantId = tenantId,
        schemaId = "document-contract",
        resourceType = "DOCUMENT",
        operation = "WRITE",
        schemaVersion = "v1",
    )

    private fun schema(): MetadataSchema = MetadataSchema(
        "document-contract",
        "v1",
        listOf(MetadataField("title", MetadataFieldType.STRING, required = true)),
    )
}

class MetadataProcessorContractTestBehaviorTest : MetadataProcessorContractTest() {
    override val metadataProcessor: MetadataProcessor = object : MetadataProcessor {
        override fun process(
            context: MetadataSchemaContext,
            metadata: Map<String, String>,
        ): Map<String, String> {
            val canonical = LinkedHashMap<String, String>()
            metadata.toSortedMap().forEach { (name, value) -> canonical[name] = value.trim() }
            return Collections.unmodifiableMap(canonical)
        }
    }

    override fun processingContext(): MetadataSchemaContext = MetadataSchemaContext(
        tenantId = "tenant-contract",
        schemaId = "document-contract",
        resourceType = "DOCUMENT",
        operation = "WRITE",
        schemaVersion = "v1",
    )

    override fun validInput(): Map<String, String> = linkedMapOf(
        "title" to "  Contract  ",
        "category" to " reference ",
    )

    override fun expectedCanonicalOutput(): Map<String, String> = linkedMapOf(
        "category" to "reference",
        "title" to "Contract",
    )
}
