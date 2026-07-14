package ai.icen.fw.application.doctor

import ai.icen.fw.application.metadata.DocumentMetadataService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaContext
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MetadataDoctorCheckerTest {
    @Test
    fun `reports legacy metadata without schema markers as incompatible history`() {
        val result = checker(mapOf("department" to "finance")).check(context())

        assertEquals(DoctorStatus.WARNING, result.status)
        assertEquals("1", result.evidence["fieldCount"])
        assertFalse(result.evidence.values.any { value -> value.contains("finance") })
    }

    @Test
    fun `reports missing exact schema and invalid fields without exposing failures or values`() {
        val missing = checker(
            metadata("private-value"),
            resolver = { null },
        ).check(context())
        val invalid = checker(
            metadata("private-value"),
            processor = { _, _ -> throw IllegalStateException("jdbc://secret/private-value") },
        ).check(context())

        assertEquals(DoctorStatus.ERROR, missing.status)
        assertEquals(DoctorStatus.ERROR, invalid.status)
        listOf(missing, invalid).forEach { result ->
            assertFalse(result.reason.contains("private-value"))
            assertFalse(result.evidence.values.any { value -> value.contains("private-value") || value.contains("jdbc") })
        }
    }

    @Test
    fun `resolves and processes the exact schema outside the persistence transaction`() {
        var transactionActive = false
        val transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T {
                transactionActive = true
                return try {
                    action()
                } finally {
                    transactionActive = false
                }
            }
        }
        val contexts = mutableListOf<MetadataSchemaContext>()
        val checker = checker(
            metadata("12.50"),
            resolver = { context ->
                assertFalse(transactionActive)
                contexts += context
                schema()
            },
            processor = { context, values ->
                assertFalse(transactionActive)
                contexts += context
                values
            },
            transaction = transaction,
        )

        val result = checker.check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals(listOf("2", "2"), contexts.map { it.schemaVersion })
    }

    @Test
    fun `warns when historical values are valid but require canonical normalization`() {
        val result = checker(
            metadata("12.500"),
            processor = { _, _ -> mapOf("amount" to "12.5") },
        ).check(context())

        assertEquals(DoctorStatus.WARNING, result.status)
    }

    @Test
    fun `ignores preserved host framework metadata when validating schema fields`() {
        val result = checker(
            metadata("12.50") + mapOf(
                "catalog.folder-id" to "contracts",
                "fileweft.retention-class" to "regulated",
            ),
        ).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("1", result.evidence["fieldCount"])
    }

    private fun checker(
        metadata: Map<String, String>,
        resolver: (MetadataSchemaContext) -> MetadataSchema? = { schema() },
        processor: (MetadataSchemaContext, Map<String, String>) -> Map<String, String> = { _, values -> values },
        transaction: ApplicationTransaction = DirectTransaction,
    ): MetadataDoctorChecker = MetadataDoctorChecker(
        documents = InMemoryDocumentRepository(documentWithActiveVersion()),
        assets = object : FileAssetRepository {
            private val asset = FileAsset(
                Identifier("asset-1"),
                Identifier("tenant-1"),
                Identifier("file-1"),
                "DOCUMENT",
                metadata,
            )

            override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
                asset.takeIf { it.tenantId == tenantId && it.id == fileAssetId }

            override fun save(fileAsset: FileAsset) = Unit
        },
        schemas = object : MetadataSchemaResolver {
            override fun resolve(context: MetadataSchemaContext): MetadataSchema? = resolver(context)
        },
        processor = object : MetadataProcessor {
            override fun process(
                context: MetadataSchemaContext,
                metadata: Map<String, String>,
            ): Map<String, String> = processor(context, metadata)
        },
        transaction = transaction,
    )

    private fun metadata(value: String): Map<String, String> = mapOf(
        "amount" to value,
        DocumentMetadataService.SCHEMA_ID_KEY to "invoice",
        DocumentMetadataService.SCHEMA_VERSION_KEY to "2",
    )

    private fun schema(): MetadataSchema = MetadataSchema(
        "invoice",
        "2",
        listOf(MetadataField("amount", MetadataFieldType.NUMBER, required = true)),
    )

    private fun context(): DoctorCheckContext = DoctorCheckContext(
        Identifier("tenant-1"),
        Identifier("document-1"),
    )

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
