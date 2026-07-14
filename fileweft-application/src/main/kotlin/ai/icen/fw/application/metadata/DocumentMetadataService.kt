package ai.icen.fw.application.metadata

import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.tenant.TenantProvider
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Resolves, validates and normalizes document metadata before a writer can
 * hand it to storage or persistence.
 *
 * The tenant always comes from [TenantProvider]. The first lookup selects the
 * configured current schema and the second, exact-version context prevents a
 * concurrent registry replacement from silently validating against another
 * version. The two markers make later Doctor checks deterministic without
 * changing the compatible `Map<String, String>` persistence model.
 */
class DocumentMetadataService(
    private val tenantProvider: TenantProvider,
    private val schemas: MetadataSchemaResolver,
    private val processor: MetadataProcessor,
) {
    fun process(
        schemaId: String,
        values: Map<String, String>,
        operation: String,
    ): Map<String, String> = processTrusted(
        tenantProvider.currentTenant().tenantId,
        schemaId,
        values,
        operation,
    )

    /** Uses a tenant snapshot already captured by an enclosing Application write use case. */
    @JvmSynthetic
    internal fun processTrusted(
        tenantId: Identifier,
        schemaId: String,
        values: Map<String, String>,
        operation: String,
    ): Map<String, String> {
        require(!values.containsKey(SCHEMA_ID_KEY) && !values.containsKey(SCHEMA_VERSION_KEY)) {
            "Metadata input must not contain framework schema markers."
        }
        val currentContext = MetadataSchemaContext(
            tenantId = tenantId.value,
            schemaId = schemaId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            operation = operation,
        )
        val schema = try {
            schemas.resolve(currentContext)
        } catch (_: RuntimeException) {
            throw MetadataConfigurationException()
        } ?: throw MetadataSchemaUnavailableException()
        if (schema.id != schemaId || schema.fields.any { field -> isReservedFieldName(field.name) }) {
            throw MetadataConfigurationException()
        }
        val exactContext = MetadataSchemaContext(
            tenantId = tenantId.value,
            schemaId = schema.id,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            operation = operation,
            schemaVersion = schema.version,
        )
        val normalized = try {
            processor.process(exactContext, values)
        } catch (_: NoSuchElementException) {
            throw MetadataConfigurationException()
        }
        if (normalized.keys.any(::isReservedFieldName)) {
            throw MetadataConfigurationException()
        }
        val persisted = LinkedHashMap<String, String>(normalized.size + 2)
        persisted.putAll(normalized)
        persisted[SCHEMA_ID_KEY] = schema.id
        persisted[SCHEMA_VERSION_KEY] = schema.version
        return Collections.unmodifiableMap(persisted)
    }

    private fun isReservedFieldName(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.startsWith(METADATA_NAMESPACE) ||
            normalized.startsWith(CATALOG_NAMESPACE) ||
            normalized.startsWith(FILEWEFT_NAMESPACE)
    }

    companion object {
        const val SCHEMA_ID_KEY: String = "metadata.schema-id"
        const val SCHEMA_VERSION_KEY: String = "metadata.schema-version"
        const val CREATE_OPERATION: String = "create"
        const val ADD_VERSION_OPERATION: String = "add-version"
        const val DOCUMENT_RESOURCE_TYPE: String = "document"
        private const val METADATA_NAMESPACE: String = "metadata."
        private const val CATALOG_NAMESPACE: String = "catalog."
        private const val FILEWEFT_NAMESPACE: String = "fileweft."
    }
}

/** Fixed-message absence used by HTTP and Doctor mappings without echoing input. */
class MetadataSchemaUnavailableException : NoSuchElementException("Metadata schema is unavailable.")

/** Fixed-message host configuration failure; transports must classify it as 500. */
class MetadataConfigurationException : IllegalStateException("Metadata configuration is invalid.")
