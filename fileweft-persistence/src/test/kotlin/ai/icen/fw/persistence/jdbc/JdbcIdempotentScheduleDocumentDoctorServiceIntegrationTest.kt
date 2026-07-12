package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.doctor.DocumentDoctorTaskHandler
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.Document
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcIdempotentScheduleDocumentDoctorServiceIntegrationTest {
    private lateinit var dataSource: DataSource
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun prepareSchema() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        reset(dataSource.connection)
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `commits verified task audit and completed idempotency receipt in one transaction`() {
        seedDocument()
        val key = "doctor-success"
        val fixture = fixture(Identifier("doctor-task-success"))

        val receipt = fixture.service.schedule(DOCUMENT_ID, key)

        val task = fixture.transaction.execute { fixture.tasks.findById(TENANT_ID, receipt.taskId) }
        val audits = fixture.transaction.execute {
            fixture.audits.findByResource(TENANT_ID, "DOCUMENT", DOCUMENT_ID, 10)
        }
        val idempotency = fixture.transaction.execute {
            fixture.idempotency.findByKeyDigest(TENANT_ID, request(key).keyDigest)
        }
        assertNotNull(task)
        assertEquals(DocumentDoctorTaskHandler.TASK_TYPE, task.type)
        assertEquals(DOCUMENT_ID, task.businessId)
        assertEquals("operator-1", task.payload["requestedBy"])
        assertEquals(BackgroundTaskStatus.PENDING, task.status)
        assertEquals(receipt.taskId, audits.single().details["taskId"]?.let(::Identifier))
        assertEquals("document:doctor:schedule", audits.single().action)
        assertEquals(OPERATOR_ID, audits.single().operatorId)
        assertEquals("诊断员", audits.single().operatorName)
        assertNotNull(idempotency)
        assertEquals(RequestIdempotencyStatus.COMPLETED, idempotency.status)
        assertEquals("DOCUMENT", idempotency.result?.resourceType)
        assertEquals(DOCUMENT_ID, idempotency.result?.resourceId)
        assertEquals("DOCTOR_TASK", idempotency.result?.relatedResourceType)
        assertEquals(receipt.taskId, idempotency.result?.relatedResourceId)
        assertEquals(1, countRows("fw_task"))
        assertEquals(1, countRows("fw_audit_record"))
        assertEquals(1, countRows("fw_idempotency_record"))

        val replay = fixture.service.schedule(DOCUMENT_ID, key)
        assertEquals(receipt.taskId, replay.taskId)
        assertEquals(1, countRows("fw_task"))
        assertEquals(1, countRows("fw_audit_record"))
        assertEquals(1, countRows("fw_idempotency_record"))
    }

    @Test
    fun `rolls back task audit and idempotency when audit append fails after its insert`() {
        seedDocument()
        val fixture = fixture(
            taskId = Identifier("doctor-task-audit-failure"),
            decorateAudit = { delegate -> FailingAfterAppendAuditRepository(delegate) },
        )

        assertFailsWith<ExpectedAuditFailure> {
            fixture.service.schedule(DOCUMENT_ID, "doctor-audit-failure")
        }

        assertEquals(0, countRows("fw_task"))
        assertEquals(0, countRows("fw_audit_record"))
        assertEquals(0, countRows("fw_idempotency_record"))
    }

    @Test
    fun `task idempotency collision fails verification and cannot complete a false receipt`() {
        seedDocument()
        val expectedTaskId = Identifier("doctor-task-idempotency-collision")
        val fixture = fixture(expectedTaskId)
        fixture.transaction.execute {
            fixture.tasks.enqueue(
                BackgroundTask(
                    id = Identifier("unrelated-task"),
                    tenantId = TENANT_ID,
                    type = "agent.execute",
                    idempotencyKey = "${DocumentDoctorTaskHandler.TASK_TYPE}:${expectedTaskId.value}",
                    businessId = OTHER_DOCUMENT_ID,
                    payload = mapOf("source" to "preexisting"),
                    nextAttemptTime = 1,
                ),
            )
        }

        assertFailsWith<IdempotencyStoreException> {
            fixture.service.schedule(DOCUMENT_ID, "doctor-idempotency-collision")
        }

        assertNull(fixture.transaction.execute { fixture.tasks.findById(TENANT_ID, expectedTaskId) })
        assertNotNull(fixture.transaction.execute { fixture.tasks.findById(TENANT_ID, Identifier("unrelated-task")) })
        assertEquals(1, countRows("fw_task"))
        assertEquals(0, countRows("fw_audit_record"))
        assertEquals(0, countRows("fw_idempotency_record"))
    }

    @Test
    fun `cross tenant task identifier collision cannot complete a local receipt`() {
        seedDocument()
        val collidingTaskId = Identifier("doctor-task-tenant-collision")
        val fixture = fixture(collidingTaskId)
        fixture.transaction.execute {
            fixture.tasks.enqueue(
                BackgroundTask(
                    id = collidingTaskId,
                    tenantId = FOREIGN_TENANT_ID,
                    type = "agent.execute",
                    idempotencyKey = "foreign-agent-task",
                    businessId = OTHER_DOCUMENT_ID,
                    nextAttemptTime = 1,
                ),
            )
        }

        val failure = assertFailsWith<PSQLException> {
            fixture.service.schedule(DOCUMENT_ID, "doctor-tenant-collision")
        }

        assertEquals("23505", failure.sqlState)
        assertNull(fixture.transaction.execute { fixture.tasks.findById(TENANT_ID, collidingTaskId) })
        assertNotNull(fixture.transaction.execute { fixture.tasks.findById(FOREIGN_TENANT_ID, collidingTaskId) })
        assertEquals(1, countRows("fw_task"))
        assertEquals(0, countRows("fw_audit_record"))
        assertEquals(0, countRows("fw_idempotency_record"))
    }

    private fun fixture(
        taskId: Identifier,
        decorateAudit: (AuditRecordRepository) -> AuditRecordRepository = { it },
    ): Fixture {
        val transaction = JdbcApplicationTransaction(dataSource)
        val tasks = JdbcTaskRepository(objectMapper, clock)
        val idempotency = JdbcRequestIdempotencyRepository()
        val audits = JdbcAuditRecordRepository(objectMapper)
        val service = IdempotentScheduleDocumentDoctorService(
            tenants = FixedTenant,
            users = FixedUsers,
            authorization = PermitAllAuthorization,
            documents = JdbcDocumentRepository(clock),
            tasks = tasks,
            identifiers = SingleIdentifierGenerator(taskId),
            clock = clock,
            idempotency = RequestIdempotencyService(
                repository = idempotency,
                transaction = transaction,
                identifierGenerator = SingleIdentifierGenerator(Identifier("idempotency-record")),
                clock = clock,
            ),
            auditTrail = AuditTrail(
                auditRecordRepository = decorateAudit(audits),
                identifierGenerator = SingleIdentifierGenerator(Identifier("audit-record")),
                clock = clock,
            ),
        )
        return Fixture(service, transaction, tasks, idempotency, audits)
    }

    private fun seedDocument() {
        JdbcApplicationTransaction(dataSource).execute {
            JdbcDocumentRepository(clock).save(
                Document(
                    id = DOCUMENT_ID,
                    tenantId = TENANT_ID,
                    assetId = Identifier("asset-1"),
                    documentNumber = "DOC-DOCTOR-1",
                    title = "诊断事务测试文档",
                ),
            )
        }
    }

    private fun request(key: String): RequestIdempotency = RequestIdempotency.create(
        tenantId = TENANT_ID,
        operatorId = OPERATOR_ID,
        idempotencyKey = key,
        action = "document:doctor:schedule",
        resourceType = "DOCUMENT",
        resourceId = DOCUMENT_ID,
        requestFingerprint = RequestFingerprint.sha256("fileweft:document:doctor:schedule:v1"),
    )

    private fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private class Fixture(
        val service: IdempotentScheduleDocumentDoctorService,
        val transaction: JdbcApplicationTransaction,
        val tasks: JdbcTaskRepository,
        val idempotency: JdbcRequestIdempotencyRepository,
        val audits: JdbcAuditRecordRepository,
    )

    private class SingleIdentifierGenerator(private val identifier: Identifier) : IdentifierGenerator {
        private var consumed = false

        override fun nextId(): Identifier {
            check(!consumed) { "Test identifier has already been consumed." }
            consumed = true
            return identifier
        }
    }

    private class FailingAfterAppendAuditRepository(
        private val delegate: AuditRecordRepository,
    ) : AuditRecordRepository {
        override fun append(record: AuditRecord) {
            delegate.append(record)
            throw ExpectedAuditFailure()
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<AuditRecord> = delegate.findByResource(tenantId, resourceType, resourceId, limit)
    }

    private class ExpectedAuditFailure : RuntimeException()

    private object FixedTenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
    }

    private object FixedUsers : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(OPERATOR_ID, "诊断员")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private object PermitAllAuthorization : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val FOREIGN_TENANT_ID = Identifier("tenant-foreign")
        val DOCUMENT_ID = Identifier("document-1")
        val OTHER_DOCUMENT_ID = Identifier("document-other")
        val OPERATOR_ID = Identifier("operator-1")
    }
}
