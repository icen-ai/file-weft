package ai.icen.fw.application.metadata

import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
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
 *
 * [logger] is optional and follows the nullable-logger pattern used by the
 * outbox worker: a null logger keeps every logging branch silent. Only schema
 * configuration failures are logged, with the original throwable and the
 * authorized tenant context; input validation failures are part of the normal
 * business flow and never reach the log.
 */
class DocumentMetadataService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    private val schemas: MetadataSchemaResolver,
    private val processor: MetadataProcessor,
    private val logger: FileWeftLogger? = null,
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
        } catch (exception: RuntimeException) {
            logConfigurationFailure("Metadata schema resolution failed.", exception, tenantId)
            throw MetadataConfigurationException()
        } ?: throw MetadataSchemaUnavailableException()
        // Unreachable by construction with the default runtime pipeline:
        // MetadataSchemaRegistry rejects reserved field names at registration
        // time and re-validates on every evaluation, and the registry never
        // returns a schema whose id differs from the requested one. The guard
        // stays as the defense boundary for custom MetadataSchemaResolver SPI
        // implementations; removing it would change the observable exception
        // type and message for those hosts (application
        // MetadataConfigurationException versus the runtime schema
        // configuration exception).
        if (schema.id != schemaId || schema.fields.any { field -> isReservedFieldName(field.name) }) {
            logConfigurationFailure("Resolved metadata schema failed the configuration guard.", null, tenantId)
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
        } catch (exception: NoSuchElementException) {
            logConfigurationFailure("Metadata schema disappeared before processing.", exception, tenantId)
            throw MetadataConfigurationException()
        } catch (exception: IllegalStateException) {
            // Schema configuration failure raised by the runtime processor.
            // Input validation failures surface as IllegalArgumentException
            // and stay out of the log as normal business flow.
            logConfigurationFailure("Metadata processing failed on schema configuration.", exception, tenantId)
            throw exception
        }
        // Unreachable by construction with the default runtime processor (the
        // runtime validator rejects reserved field names before any value is
        // normalized); retained as the defense boundary for custom
        // MetadataProcessor SPI implementations, see the guard above.
        if (normalized.keys.any(::isReservedFieldName)) {
            logConfigurationFailure("Normalized metadata failed the configuration guard.", null, tenantId)
            throw MetadataConfigurationException()
        }
        val persisted = LinkedHashMap<String, String>(normalized.size + 2)
        persisted.putAll(normalized)
        persisted[SCHEMA_ID_KEY] = schema.id
        persisted[SCHEMA_VERSION_KEY] = schema.version
        return Collections.unmodifiableMap(persisted)
    }

    /**
     * Logs schema configuration failures at the processing boundary. A null
     * logger keeps the branch silent; the tenant always comes from the
     * authorized snapshot, never from request input.
     */
    private fun logConfigurationFailure(
        message: String,
        failure: Throwable?,
        tenantId: Identifier,
    ) {
        logger?.error(message, failure, LogContext(tenantId = tenantId))
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
