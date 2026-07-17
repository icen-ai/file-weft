package ai.icen.fw.persistence.migration

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.application.upload.ResumableUploadSessionStatus
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.jdbc.JdbcApplicationTransaction
import ai.icen.fw.persistence.jdbc.JdbcResumableUploadSessionRepository
import ai.icen.fw.spi.storage.StorageObjectLocation
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableUploadOwnerMigrationIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareSchemaAtV023() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        reset(dataSource.connection)
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(FlywayMigrationRunner.HISTORY_TABLE)
            .baselineVersion("0")
            .baselineDescription("FileWeft namespace initialization")
            .target(MigrationVersion.fromVersion("23"))
            .load()
        flyway.baseline()
        flyway.migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `migration preserves legacy sessions as unowned while system cleanup still reconciles them`() {
        insertLegacySession("legacy-expired", "legacy-request", expiresAt = 50)

        assertEquals(10, FlywayMigrationRunner(dataSource).migrate())
        dataSource.connection.use { connection ->
            assertEquals("YES", ownerColumnNullable(connection))
            assertTrue(constraintExists(connection, "ck_fw_upload_session_owner_id"))
            assertTrue(constraintExists(connection, "ck_fw_upload_session_status"))
            assertTrue(
                constraintDefinition(connection, "ck_fw_upload_session_status").contains("QUARANTINED"),
            )
            assertEquals(
                "UNIQUE (tenant_id, idempotency_key)",
                uniqueConstraintDefinition(connection, "fw_upload_session_tenant_id_idempotency_key_key"),
            )
        }

        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val legacy = transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("legacy-expired"))
        }
        assertNull(legacy?.ownerId)
        assertNull(transaction.execute {
            repository.findById(Identifier("tenant-1"), "owner-1", Identifier("legacy-expired"))
        })
        assertNull(transaction.execute {
            repository.findByIdempotencyKey(Identifier("tenant-1"), "owner-1", "legacy-request")
        })

        transaction.execute {
            repository.save(session("owned-expired", "owned-request", "owner-1", expiresAt = 60))
        }
        val expired = transaction.execute { repository.findExpired(100, 10) }
        assertEquals(listOf("legacy-expired", "owned-expired"), expired.map { it.id.value })
        assertEquals(listOf(null, "owner-1"), expired.map { it.ownerId })

        val claimedLegacy = transaction.execute {
            repository.claimForAbort(Identifier("tenant-1"), Identifier("legacy-expired"), 100)
        }
        assertNull(claimedLegacy?.ownerId)
        assertTrue(transaction.execute {
            repository.markAborted(Identifier("tenant-1"), Identifier("legacy-expired"), expired = true, updatedAt = 101)
        })
        val reconciledLegacy = transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("legacy-expired"))
        }
        assertEquals(ResumableUploadSessionStatus.EXPIRED, reconciledLegacy?.status)
        assertNull(reconciledLegacy?.ownerId)
    }

    @Test
    fun `database constraint rejects blank padded control format and oversized owners`() {
        assertEquals(10, FlywayMigrationRunner(dataSource).migrate())
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        transaction.execute {
            repository.save(session("owned-session", "owned-request", "owner-valid", expiresAt = 500))
        }

        val supplementaryFormat = String(Character.toChars(0xE0001))
        listOf(
            "",
            " owner",
            "owner ",
            "\u00A0owner",
            "owner\u0001",
            "owner\u200B",
            "owner$supplementaryFormat",
        ).forEach { invalidOwner ->
            val failure = assertFailsWith<PSQLException> { updateOwner(invalidOwner) }
            assertEquals("23514", failure.sqlState)
            assertEquals("ck_fw_upload_session_owner_id", failure.serverErrorMessage?.constraint)
        }

        val oversized = assertFailsWith<PSQLException> { updateOwner("x".repeat(257)) }
        assertEquals("22001", oversized.sqlState)
        assertEquals("owner-valid", transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("owned-session"))
        }?.ownerId)
    }

    @Test
    fun `database constraint counts supplementary owners as two UTF-16 units without blocking cleanup`() {
        assertEquals(10, FlywayMigrationRunner(dataSource).migrate())
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        val supplementaryCharacter = String(Character.toChars(0x1F600))
        val boundaryOwner = supplementaryCharacter.repeat(128)
        val oversizedOwner = supplementaryCharacter.repeat(129)
        assertEquals(256, boundaryOwner.length)
        assertEquals(258, oversizedOwner.length)

        transaction.execute {
            repository.save(session("utf16-boundary", "utf16-boundary-request", boundaryOwner, expiresAt = 50))
        }
        val restored = transaction.execute {
            repository.findById(Identifier("tenant-1"), boundaryOwner, Identifier("utf16-boundary"))
        }
        assertEquals(boundaryOwner, restored?.ownerId)

        val rejected = assertFailsWith<PSQLException> {
            updateOwner(oversizedOwner, sessionId = "utf16-boundary")
        }
        assertEquals("23514", rejected.sqlState)
        assertEquals("ck_fw_upload_session_owner_id", rejected.serverErrorMessage?.constraint)

        val expired = transaction.execute { repository.findExpired(50, 10) }.single()
        assertEquals(boundaryOwner, expired.ownerId)
        val claimed = transaction.execute {
            repository.claimForAbort(Identifier("tenant-1"), Identifier("utf16-boundary"), 50)
        }
        assertEquals(boundaryOwner, claimed?.ownerId)
        assertTrue(transaction.execute {
            repository.markAborted(Identifier("tenant-1"), Identifier("utf16-boundary"), expired = true, updatedAt = 51)
        })
        val reconciled = transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("utf16-boundary"))
        }
        assertEquals(ResumableUploadSessionStatus.EXPIRED, reconciled?.status)
        assertEquals(boundaryOwner, reconciled?.ownerId)
    }

    @Test
    fun `status constraint accepts quarantined and rejects unknown states`() {
        assertEquals(10, FlywayMigrationRunner(dataSource).migrate())
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcResumableUploadSessionRepository(ObjectMapper())
        transaction.execute {
            repository.save(session("status-session", "status-request", "owner-1", expiresAt = 500))
            checkNotNull(repository.claimForAbort(Identifier("tenant-1"), Identifier("status-session"), 100))
            assertTrue(
                repository.markQuarantined(
                    Identifier("tenant-1"),
                    Identifier("status-session"),
                    "fileweft:resumable-upload:owner-isolation:v1",
                    101,
                ),
            )
        }
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, transaction.execute {
            repository.findById(Identifier("tenant-1"), Identifier("status-session"))
        }?.status)

        val rejected = assertFailsWith<PSQLException> {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE fw_upload_session SET session_status = 'UNKNOWN' WHERE id = 'status-session'",
                ).use { it.executeUpdate() }
            }
        }
        assertEquals("23514", rejected.sqlState)
        assertEquals("ck_fw_upload_session_status", rejected.serverErrorMessage?.constraint)
    }

    private fun insertLegacySession(id: String, key: String, expiresAt: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_upload_session(
                    id, tenant_id, idempotency_key, storage_upload_id, storage_type, storage_path,
                    file_object_id, file_asset_id, file_name, content_length, asset_type, content_type,
                    content_hash, metadata_json, session_status, expires_at, last_error, completed_time,
                    created_time, updated_time
                ) VALUES (?, 'tenant-1', ?, ?, 's3', ?, ?, ?, 'legacy.bin', 10, 'DOCUMENT',
                          'application/octet-stream', NULL, '{}'::jsonb, 'ACTIVE', ?, NULL, NULL, 10, 10)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, key)
                statement.setString(3, "storage-$id")
                statement.setString(4, "objects/${id.padEnd(64, 'a').take(64)}")
                statement.setString(5, "file-$id")
                statement.setString(6, "asset-$id")
                statement.setLong(7, expiresAt)
                statement.executeUpdate()
            }
        }
    }

    private fun updateOwner(ownerId: String, sessionId: String = "owned-session") {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE fw_upload_session SET owner_id = ? WHERE tenant_id = 'tenant-1' AND id = ?",
            ).use { statement ->
                statement.setString(1, ownerId)
                statement.setString(2, sessionId)
                statement.executeUpdate()
            }
        }
    }

    private fun session(id: String, key: String, owner: String, expiresAt: Long) = ResumableUploadSession(
        id = Identifier(id),
        tenantId = Identifier("tenant-1"),
        idempotencyKey = key,
        storageUploadId = Identifier("storage-$id"),
        storageLocation = StorageObjectLocation("s3", "objects/${id.padEnd(64, 'a').take(64)}"),
        fileObjectId = Identifier("file-$id"),
        fileAssetId = Identifier("asset-$id"),
        fileName = "owned.bin",
        contentLength = 10,
        assetType = "DOCUMENT",
        contentType = "application/octet-stream",
        expiresAt = expiresAt,
        createdTime = 10,
        updatedTime = 10,
        ownerId = owner,
    )

    private fun ownerColumnNullable(connection: Connection): String =
        connection.metaData.getColumns(null, "public", "fw_upload_session", "owner_id").use { columns ->
            check(columns.next()) { "owner_id column was not created." }
            columns.getString("IS_NULLABLE")
        }

    private fun constraintExists(connection: Connection, constraintName: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_upload_session'::regclass)",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }

    private fun uniqueConstraintDefinition(connection: Connection, constraintName: String): String =
        connection.prepareStatement(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_upload_session'::regclass",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result ->
                check(result.next()) { "Constraint $constraintName was not found." }
                result.getString(1)
            }
        }

    private fun constraintDefinition(connection: Connection, constraintName: String): String =
        connection.prepareStatement(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ? AND conrelid = 'fw_upload_session'::regclass",
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { result ->
                check(result.next()) { "Constraint $constraintName was not found." }
                result.getString(1)
            }
        }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
