package ai.icen.fw.application.doctor

import ai.icen.fw.application.metadata.DocumentMetadataService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.spi.doctor.DoctorChecker
import java.util.LinkedHashMap
import java.util.Locale

/** Validates persisted document metadata against its recorded exact schema. */
class MetadataDoctorChecker(
    private val documents: DocumentRepository,
    private val assets: FileAssetRepository,
    private val schemas: MetadataSchemaResolver,
    private val processor: MetadataProcessor,
    private val transaction: ApplicationTransaction,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val documentId = context.documentId ?: return skipped(
            "Metadata diagnosis needs a document identifier.",
            "Run this checker as part of document diagnosis.",
        )
        val snapshot = transaction.execute {
            val document = documents.findById(context.tenantId, documentId)
                ?: return@execute MetadataSnapshot(result = skipped(
                    "Metadata diagnosis was skipped because the document is unavailable.",
                    "Resolve the lifecycle diagnosis before checking metadata.",
                ))
            val asset = assets.findById(context.tenantId, document.assetId)
                ?: return@execute MetadataSnapshot(result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.ERROR,
                    "Document metadata cannot be inspected because its file asset is missing.",
                    evidence = mapOf("assetId" to document.assetId.value),
                    repairSuggestion = "Restore the file asset record through a controlled data migration.",
                ))
            MetadataSnapshot(asset.id, LinkedHashMap(asset.metadata))
        }
        snapshot.result?.let { return it }
        val assetId = checkNotNull(snapshot.assetId)
        val stored = checkNotNull(snapshot.metadata)
        val schemaId = stored[DocumentMetadataService.SCHEMA_ID_KEY]
        val schemaVersion = stored[DocumentMetadataService.SCHEMA_VERSION_KEY]
        val values = stored.filterKeys { key ->
            key != DocumentMetadataService.SCHEMA_ID_KEY &&
                key != DocumentMetadataService.SCHEMA_VERSION_KEY &&
                !isHostFrameworkMetadataKey(key)
        }

        if (schemaId == null && schemaVersion == null) {
            return if (values.isEmpty()) {
                skipped("Document has no schema-governed metadata.")
            } else {
                DoctorCheckResult(
                    NAME,
                    DoctorStatus.WARNING,
                    "Document contains compatible legacy metadata without a schema marker.",
                    evidence = mapOf("assetId" to assetId.value, "fieldCount" to values.size.toString()),
                    repairSuggestion = "Validate and rewrite the metadata through a configured current schema.",
                )
            }
        }
        if (schemaId == null || schemaVersion == null) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Document metadata has incomplete schema markers.",
                evidence = mapOf("assetId" to assetId.value),
                repairSuggestion = "Repair both schema markers through a controlled metadata migration.",
            )
        }

        val schemaContext = try {
            MetadataSchemaContext(
                tenantId = context.tenantId.value,
                schemaId = schemaId,
                resourceType = DocumentMetadataService.DOCUMENT_RESOURCE_TYPE,
                operation = DOCTOR_OPERATION,
                schemaVersion = schemaVersion,
            )
        } catch (_: IllegalArgumentException) {
            return invalidMarkers(assetId)
        }
        val schema = try {
            schemas.resolve(schemaContext)
        } catch (_: Exception) {
            return unavailableSchema(assetId)
        } ?: return unavailableSchema(assetId)

        if (schema.id != schemaId || schema.version != schemaVersion) {
            return unavailableSchema(assetId)
        }
        val normalized = try {
            processor.process(schemaContext, values)
        } catch (_: Exception) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Document metadata is incompatible with its recorded schema.",
                evidence = mapOf("assetId" to assetId.value, "fieldCount" to values.size.toString()),
                repairSuggestion = "Correct the invalid fields through the metadata application boundary.",
            )
        }
        return if (normalized == values) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "Document metadata matches its recorded schema.",
                evidence = mapOf("assetId" to assetId.value, "fieldCount" to values.size.toString()),
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.WARNING,
                "Document metadata is valid but not in the current canonical representation.",
                evidence = mapOf("assetId" to assetId.value, "fieldCount" to values.size.toString()),
                repairSuggestion = "Rewrite the metadata through the metadata application boundary to normalize it.",
            )
        }
    }

    private fun invalidMarkers(assetId: Identifier): DoctorCheckResult = DoctorCheckResult(
        NAME,
        DoctorStatus.ERROR,
        "Document metadata has invalid schema markers.",
        evidence = mapOf("assetId" to assetId.value),
        repairSuggestion = "Repair schema markers through a controlled metadata migration.",
    )

    private fun unavailableSchema(assetId: Identifier): DoctorCheckResult = DoctorCheckResult(
        NAME,
        DoctorStatus.ERROR,
        "The exact schema recorded by document metadata is unavailable.",
        evidence = mapOf("assetId" to assetId.value),
        repairSuggestion = "Restore the recorded schema version before editing or migrating this metadata.",
    )

    private fun isHostFrameworkMetadataKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.startsWith(CATALOG_NAMESPACE) || normalized.startsWith(FILEWEFT_NAMESPACE)
    }

    private fun skipped(reason: String, repair: String? = null): DoctorCheckResult = DoctorCheckResult(
        NAME,
        DoctorStatus.SKIPPED,
        reason,
        repairSuggestion = repair,
    )

    companion object {
        const val NAME: String = "metadata"
        private const val DOCTOR_OPERATION: String = "doctor"
        private const val CATALOG_NAMESPACE: String = "catalog."
        private const val FILEWEFT_NAMESPACE: String = "fileweft."
    }

    private data class MetadataSnapshot(
        val assetId: Identifier? = null,
        val metadata: Map<String, String>? = null,
        val result: DoctorCheckResult? = null,
    )
}
