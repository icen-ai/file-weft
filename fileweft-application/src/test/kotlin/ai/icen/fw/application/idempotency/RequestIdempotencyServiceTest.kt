package ai.icen.fw.application.idempotency

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestIdempotencyServiceTest {
    @Test
    fun `validates keys without retaining or disclosing the raw value`() {
        listOf(
            "a",
            "A-1_2.3:4~5",
            "a" + "b".repeat(127),
        ).forEach { key ->
            val request = request(key)
            assertTrue(request.keyDigest.matches(Regex("sha256:[0-9a-f]{64}")))
            assertNotEquals(key, request.keyDigest)
        }

        val secretKey = "Customer-Secret-Key-2026"
        val secretRequest = request(secretKey)
        assertFalse(secretRequest.keyDigest.contains(secretKey))
        assertFalse(secretRequest.toString().contains(secretKey))

        listOf(
            "",
            " leading",
            "-leading",
            "trailing ",
            "contains/slash",
            "contains\ncontrol",
            "a" + "b".repeat(128),
        ).forEach { key ->
            val failure = assertThrows<InvalidIdempotencyRequestException> { request(key) }
            assertEquals("Idempotency key has an invalid format.", failure.message)
        }
    }

    @Test
    fun `scopes key digests by tenant and never stores the key`() {
        val first = request("shared-key", tenantId = "tenant-1")
        val repeated = request("shared-key", tenantId = "tenant-1")
        val otherTenant = request("shared-key", tenantId = "tenant-2")

        assertEquals(first.keyDigest, repeated.keyDigest)
        assertNotEquals(first.keyDigest, otherTenant.keyDigest)
        assertFalse(RequestIdempotency::class.java.declaredFields.any { field ->
            field.name == "idempotencyKey" || field.name == "key"
        })
    }

    @Test
    fun `fingerprints distinguish null empty component boundaries and order`() {
        assertEquals(RequestFingerprint.sha256("a", null), RequestFingerprint.sha256("a", null))
        assertNotEquals(RequestFingerprint.sha256("a", null), RequestFingerprint.sha256("a", ""))
        assertNotEquals(RequestFingerprint.sha256("ab", "c"), RequestFingerprint.sha256("a", "bc"))
        assertNotEquals(RequestFingerprint.sha256("name", "value"), RequestFingerprint.sha256("value", "name"))
        assertTrue(RequestFingerprint.sha256("中文").matches(Regex("sha256:[0-9a-f]{64}")))
        assertNotEquals(RequestFingerprint.sha256("?"), RequestFingerprint.sha256("\uD83D\uDE00"))
        assertThrows<IllegalArgumentException> { RequestFingerprint.sha256("\uD800") }
        assertThrows<IllegalArgumentException> { RequestFingerprint.sha256("\uDC00") }
        assertThrows<IllegalArgumentException> { RequestFingerprint.sha256("a\uD800b") }
        assertThrows<IllegalArgumentException> {
            RequestIdempotency.create(
                Identifier("tenant-1"),
                Identifier("operator-1"),
                "unicode-key",
                "document:submit",
                "DOCUMENT",
                Identifier("document-\uD800"),
                RequestFingerprint.sha256(),
            )
        }
    }

    @Test
    fun `enforces stable result and record shapes`() {
        assertThrows<IllegalArgumentException> {
            IdempotencyResult("DOCUMENT", Identifier("document-1"), "WORKFLOW", null)
        }
        assertThrows<IllegalArgumentException> {
            inProgressRecord(request("shape"), result = result(), completedTime = null)
        }
        assertThrows<IllegalArgumentException> {
            completedRecord(request("shape"), result = null)
        }
    }

    @Test
    fun `claims executes and completes fresh work in one transaction`() {
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events)
        val service = service(repository, events, SequenceClock(100, 110))
        var replayMappings = 0

        val execution = service.execute(
            request("fresh"),
            IdempotencyReplayMapper {
                replayMappings += 1
                "replayed"
            },
            IdempotentCommand {
                events += "command"
                IdempotentCommandResult("created", result("document-created"))
            },
        )

        assertEquals("created", execution.value)
        assertFalse(execution.replayed)
        assertEquals(0, replayMappings)
        assertEquals(
            listOf("tx:start", "claim", "command", "complete", "tx:commit"),
            events,
        )
        assertEquals(RequestIdempotencyStatus.COMPLETED, repository.record?.status)
        assertEquals(110, repository.record?.completedTime)
    }

    @Test
    fun `replays the first safe result without executing the command again`() {
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events)
        val service = service(repository, events, SequenceClock(100, 110, 120))
        val request = request("replay")
        var commandCalls = 0

        service.execute(
            request,
            IdempotencyReplayMapper { "unused" },
            IdempotentCommand {
                commandCalls += 1
                IdempotentCommandResult("first", result("document-first"))
            },
        )
        events.clear()

        val replay = service.execute(
            request,
            IdempotencyReplayMapper { stored -> "replay:${stored.resourceId.value}" },
            IdempotentCommand {
                commandCalls += 1
                IdempotentCommandResult("duplicate", result("document-duplicate"))
            },
        )

        assertEquals("replay:document-first", replay.value)
        assertTrue(replay.replayed)
        assertEquals(1, commandCalls)
        assertEquals(listOf("tx:start", "claim", "tx:commit"), events)
        assertEquals("document-first", service.findCompleted(request)?.resourceId?.value)
    }

    @Test
    fun `rejects every changed binding with the same fixed conflict`() {
        val base = request("binding")
        val changes = listOf(
            request("binding", operatorId = "operator-2"),
            request("binding", action = "document:offline"),
            request("binding", resourceType = "WORKFLOW"),
            request("binding", resourceId = "document-2"),
            request("binding", subresourceId = "task-2"),
            request("binding", fingerprint = RequestFingerprint.sha256("changed")),
        )

        changes.forEach { changed ->
            val events = mutableListOf<String>()
            val repository = RecordingRepository(events).apply {
                record = completedRecord(base, result("document-first"))
            }
            val failure = assertThrows<IdempotencyKeyConflictException> {
                service(repository, events, SequenceClock(200)).findCompleted(changed)
            }
            assertEquals("Idempotency key is already bound to a different request.", failure.message)
            assertFalse(failure.message.orEmpty().contains(changed.operatorId.value))
        }
    }

    @Test
    fun `fails closed for a committed in progress record`() {
        val request = request("in-progress")
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events).apply { record = inProgressRecord(request) }
        val service = service(repository, events, SequenceClock(200, 201))

        assertThrows<IdempotencyInProgressException> { service.findCompleted(request) }
        assertThrows<IdempotencyInProgressException> {
            service.execute(
                request,
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand { error("must not execute") },
            )
        }
        assertFalse(events.contains("complete"))
    }

    @Test
    fun `rejects a repository record outside the requested tenant and digest scope`() {
        val requested = request("scope", tenantId = "tenant-1")
        val foreign = request("scope", tenantId = "tenant-2")
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events).apply {
            record = completedRecord(foreign, result("document-foreign"))
            ignoreLookupScope = true
        }

        assertThrows<IdempotencyStoreException> {
            service(repository, events, SequenceClock(200)).findCompleted(requested)
        }
    }

    @Test
    fun `rolls back by exception when the command fails before completion`() {
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events)
        val service = service(repository, events, SequenceClock(100))

        assertThrows<ExpectedFailure> {
            service.execute(
                request("failure"),
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand {
                    events += "command"
                    throw ExpectedFailure()
                },
            )
        }

        assertFalse(events.contains("complete"))
        assertEquals(listOf("tx:start", "claim", "command", "tx:rollback"), events)
    }

    @Test
    fun `rejects malicious claim and completion responses`() {
        val request = request("malicious")
        val badClaimEvents = mutableListOf<String>()
        val badClaimRepository = RecordingRepository(badClaimEvents).apply {
            claimOverride = RequestIdempotencyClaim(
                inProgressRecord(request, id = "wrong-id"),
                acquired = true,
            )
        }
        assertThrows<IdempotencyStoreException> {
            service(badClaimRepository, badClaimEvents, SequenceClock(100)).execute(
                request,
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand { IdempotentCommandResult("fresh", result()) },
            )
        }

        val badCompleteEvents = mutableListOf<String>()
        val badCompleteRepository = RecordingRepository(badCompleteEvents).apply {
            completionResultOverride = result("different-document")
        }
        assertThrows<IdempotencyStoreException> {
            service(badCompleteRepository, badCompleteEvents, SequenceClock(100, 110)).execute(
                request,
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand { IdempotentCommandResult("fresh", result("document-1")) },
            )
        }
        assertTrue(badCompleteEvents.contains("tx:rollback"))

        val badClaimTimeEvents = mutableListOf<String>()
        val badClaimTimeRepository = RecordingRepository(badClaimTimeEvents).apply {
            claimCreatedTimeOverride = 99
        }
        assertThrows<IdempotencyStoreException> {
            service(badClaimTimeRepository, badClaimTimeEvents, SequenceClock(100)).execute(
                request("malicious-claim-time"),
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand { IdempotentCommandResult("fresh", result()) },
            )
        }

        val badCompleteTimeEvents = mutableListOf<String>()
        val badCompleteTimeRepository = RecordingRepository(badCompleteTimeEvents).apply {
            completionCreatedTimeOverride = 99
        }
        assertThrows<IdempotencyStoreException> {
            service(badCompleteTimeRepository, badCompleteTimeEvents, SequenceClock(100, 110)).execute(
                request("malicious-complete-time"),
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand { IdempotentCommandResult("fresh", result()) },
            )
        }
    }

    @Test
    fun `clamps a backwards clock and rejects a negative clock`() {
        val events = mutableListOf<String>()
        val repository = RecordingRepository(events)
        val execution = service(repository, events, SequenceClock(100, 90)).execute(
            request("clock-backwards"),
            IdempotencyReplayMapper { "replay" },
            IdempotentCommand { IdempotentCommandResult("fresh", result()) },
        )
        assertEquals("fresh", execution.value)
        assertEquals(100, repository.record?.completedTime)

        val negativeEvents = mutableListOf<String>()
        assertThrows<IdempotencyStoreException> {
            service(RecordingRepository(negativeEvents), negativeEvents, SequenceClock(-1)).execute(
                request("clock-negative"),
                IdempotencyReplayMapper { "replay" },
                IdempotentCommand { IdempotentCommandResult("fresh", result()) },
            )
        }
        assertFalse(negativeEvents.contains("claim"))
    }

    private fun service(
        repository: RecordingRepository,
        events: MutableList<String>,
        clock: Clock,
    ) = RequestIdempotencyService(
        repository = repository,
        transaction = RecordingTransaction(events),
        identifierGenerator = object : IdentifierGenerator {
            private var sequence = 0
            override fun nextId(): Identifier = Identifier("idempotency-${++sequence}")
        },
        clock = clock,
    )

    private fun request(
        key: String,
        tenantId: String = "tenant-1",
        operatorId: String = "operator-1",
        action: String = "document:submit",
        resourceType: String = "DOCUMENT",
        resourceId: String = "document-1",
        subresourceId: String? = "task-1",
        fingerprint: String = RequestFingerprint.sha256("comment", null),
    ): RequestIdempotency = RequestIdempotency.create(
        tenantId = Identifier(tenantId),
        operatorId = Identifier(operatorId),
        idempotencyKey = key,
        action = action,
        resourceType = resourceType,
        resourceId = Identifier(resourceId),
        requestFingerprint = fingerprint,
        subresourceId = subresourceId?.let(::Identifier),
    )

    private fun result(documentId: String = "document-1") = IdempotencyResult(
        resourceType = "DOCUMENT",
        resourceId = Identifier(documentId),
        relatedResourceType = "WORKFLOW",
        relatedResourceId = Identifier("workflow-1"),
    )

    private fun inProgressRecord(
        request: RequestIdempotency,
        id: String = "idempotency-stored",
        result: IdempotencyResult? = null,
        completedTime: Long? = null,
    ) = RequestIdempotencyRecord(
        id = Identifier(id),
        tenantId = request.tenantId,
        keyDigest = request.keyDigest,
        operatorId = request.operatorId,
        action = request.action,
        resourceType = request.resourceType,
        resourceId = request.resourceId,
        subresourceId = request.subresourceId,
        requestFingerprint = request.requestFingerprint,
        status = RequestIdempotencyStatus.IN_PROGRESS,
        result = result,
        completedTime = completedTime,
        createdTime = 100,
        updatedTime = 100,
    )

    private fun completedRecord(
        request: RequestIdempotency,
        result: IdempotencyResult?,
        id: String = "idempotency-stored",
        completedTime: Long = 110,
    ) = RequestIdempotencyRecord(
        id = Identifier(id),
        tenantId = request.tenantId,
        keyDigest = request.keyDigest,
        operatorId = request.operatorId,
        action = request.action,
        resourceType = request.resourceType,
        resourceId = request.resourceId,
        subresourceId = request.subresourceId,
        requestFingerprint = request.requestFingerprint,
        status = RequestIdempotencyStatus.COMPLETED,
        result = result,
        completedTime = completedTime,
        createdTime = 100,
        updatedTime = completedTime,
    )

    private class RecordingRepository(
        private val events: MutableList<String>,
    ) : RequestIdempotencyRepository {
        var record: RequestIdempotencyRecord? = null
        var ignoreLookupScope: Boolean = false
        var claimOverride: RequestIdempotencyClaim? = null
        var completionResultOverride: IdempotencyResult? = null
        var claimCreatedTimeOverride: Long? = null
        var completionCreatedTimeOverride: Long? = null

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? {
            events += "find"
            return record?.takeIf {
                ignoreLookupScope || (it.tenantId == tenantId && it.keyDigest == keyDigest)
            }
        }

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim {
            events += "claim"
            claimOverride?.let { return it }
            record?.let { return RequestIdempotencyClaim(it, acquired = false) }
            val createdTime = claimCreatedTimeOverride ?: now
            val created = RequestIdempotencyRecord(
                id = newRecordId,
                tenantId = request.tenantId,
                keyDigest = request.keyDigest,
                operatorId = request.operatorId,
                action = request.action,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                subresourceId = request.subresourceId,
                requestFingerprint = request.requestFingerprint,
                status = RequestIdempotencyStatus.IN_PROGRESS,
                result = null,
                completedTime = null,
                createdTime = createdTime,
                updatedTime = createdTime,
            )
            record = created
            return RequestIdempotencyClaim(created, acquired = true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            events += "complete"
            val current = requireNotNull(record)
            val completed = RequestIdempotencyRecord(
                id = recordId,
                tenantId = tenantId,
                keyDigest = keyDigest,
                operatorId = current.operatorId,
                action = current.action,
                resourceType = current.resourceType,
                resourceId = current.resourceId,
                subresourceId = current.subresourceId,
                requestFingerprint = current.requestFingerprint,
                status = RequestIdempotencyStatus.COMPLETED,
                result = completionResultOverride ?: result,
                completedTime = completedAt,
                createdTime = completionCreatedTimeOverride ?: current.createdTime,
                updatedTime = completedAt,
            )
            record = completed
            return completed
        }
    }

    private class RecordingTransaction(
        private val events: MutableList<String>,
    ) : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T {
            events += "tx:start"
            return try {
                action().also { events += "tx:commit" }
            } catch (failure: Throwable) {
                events += "tx:rollback"
                throw failure
            }
        }
    }

    private class SequenceClock(vararg values: Long) : Clock() {
        private val values = ArrayDeque(values.toList())
        private var last: Long = values.lastOrNull() ?: 0

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(millis())

        override fun millis(): Long = if (values.isEmpty()) last else values.removeFirst().also { last = it }
    }

    private class ExpectedFailure : RuntimeException()
}
