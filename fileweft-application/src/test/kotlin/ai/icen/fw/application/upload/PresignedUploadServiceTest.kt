package ai.icen.fw.application.upload

import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionNestingException
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.transaction.ApplicationTransactionState
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest
import ai.icen.fw.spi.storage.PresignedUploadCleanupRequest
import ai.icen.fw.spi.storage.PresignedUploadGrant
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest
import ai.icen.fw.spi.storage.PresignedUploadReissueRequest
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

class PresignedUploadServiceTest {
    @Test
    fun `persists exact grant authority before returning a location-free client result`() {
        val fixture = fixture()

        val result = fixture.service.start(command())
        val session = fixture.repository.findById(Identifier("tenant-a"), result.sessionId)

        assertNotNull(session)
        assertEquals(fixture.adapter.grant.location, session?.storageLocation)
        assertEquals("owner-a", session?.ownerId)
        assertEquals("PUT", result.httpMethod)
        assertFalse(result.javaClass.methods.any { it.name == "getStorageLocation" || it.name == "getLocation" })
        assertFalse(CompletePresignedUploadCommand::class.java.methods.any { it.name.contains("Location") })
    }

    @Test
    fun `owner scoped caller key replays by exact resign without extending or changing staging authority`() {
        val fixture = fixture()

        val first = fixture.service.startWithCallerKey("request-1", command())
        val second = fixture.service.startWithCallerKey("request-1", command())
        val durable = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), first.sessionId))

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.expiresAt, second.expiresAt)
        assertEquals(fixture.adapter.grant.location, durable.stagingLocation)
        assertEquals(1, fixture.adapter.createCount)
        assertEquals(1, fixture.adapter.reissueCount)
        assertEquals(1, fixture.repository.size())
        assertFalse(durable.idempotencyKeyDigest.contains("request-1"))
    }

    @Test
    fun `same caller key with declaration drift conflicts before storage`() {
        val fixture = fixture()
        fixture.service.startWithCallerKey("request-1", command())

        assertThrows(PresignedUploadStateException::class.java) {
            fixture.service.startWithCallerKey(
                "request-1",
                StartPresignedUploadCommand(
                    "different.txt",
                    7,
                    "text/plain",
                    CONTENT_HASH,
                    CHECKSUM,
                    grantDuration = Duration.ofMinutes(5),
                ),
            )
        }

        assertEquals(1, fixture.adapter.createCount)
        assertEquals(0, fixture.adapter.reissueCount)
    }

    @Test
    fun `bounds grant expiration after adapter latency and persists the post-signing time`() {
        val fixture = fixture()
        fixture.adapter.clockToAdvance = fixture.clock
        fixture.adapter.createLatencyMillis = 250

        val result = fixture.service.start(command())
        val session = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), result.sessionId))

        assertEquals(NOW + 250, session.createdTime)
        assertEquals(session.createdTime, session.updatedTime)
        assertEquals(NOW + 250 + Duration.ofMinutes(5).toMillis(), result.expiresAt)
    }

    @Test
    fun `finalizes from durable location with CAS and replays exact completion`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())

        val first = fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        val second = fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))

        val durable = fixture.repository.findById(Identifier("tenant-a"), grant.sessionId)
        assertEquals(fixture.adapter.grant.location, fixture.adapter.finalizeRequest?.location)
        assertEquals(1, fixture.adapter.finalizeCount)
        assertEquals(first.finalization.revision, second.finalization.revision)
        assertEquals(PresignedUploadSessionStatus.COMPLETED, durable?.status)
        assertEquals(2L, durable?.version)
        assertEquals(fixture.adapter.grant.location, durable?.stagingLocation)
        assertEquals(first.finalization.storedObject.location, durable?.finalLocation)
        assertFalse(durable?.stagingLocation == durable?.finalLocation)
    }

    @Test
    fun `fails closed for another tenant or owner without calling storage`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())

        fixture.tenantProvider.tenantId = Identifier("tenant-b")
        assertThrows(PresignedUploadNotFoundException::class.java) {
            fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }
        fixture.tenantProvider.tenantId = Identifier("tenant-a")
        fixture.userRealm.user = UserIdentity(Identifier("owner-b"))
        assertThrows(PresignedUploadNotFoundException::class.java) {
            fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }
        assertEquals(0, fixture.adapter.finalizeCount)
    }

    @Test
    fun `does not sign or finalize when fresh authorization denies the action`() {
        val deniedStart = fixture()
        deniedStart.authorization.allowed = false
        assertThrows(ApplicationForbiddenException::class.java) {
            deniedStart.service.start(command())
        }
        assertEquals(0, deniedStart.adapter.createCount)

        val deniedComplete = fixture()
        val grant = deniedComplete.service.start(command())
        deniedComplete.authorization.allowed = false
        assertThrows(ApplicationForbiddenException::class.java) {
            deniedComplete.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }
        assertEquals(0, deniedComplete.adapter.finalizeCount)
    }

    @Test
    fun `rechecks authorization after signing and tenant plus owner after provider verification`() {
        val revokedAfterSigning = fixture()
        revokedAfterSigning.adapter.afterCreate = { revokedAfterSigning.authorization.allowed = false }
        assertThrows(ApplicationForbiddenException::class.java) {
            revokedAfterSigning.service.start(command())
        }
        assertEquals(1, revokedAfterSigning.adapter.createCount)
        assertEquals(0, revokedAfterSigning.repository.size())

        val changedTenant = fixture()
        val grant = changedTenant.service.start(command())
        changedTenant.adapter.afterFinalize = {
            changedTenant.tenantProvider.tenantId = Identifier("tenant-b")
        }
        assertThrows(PresignedUploadNotFoundException::class.java) {
            changedTenant.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }
        val retryable = requireNotNull(
            changedTenant.repository.findById(Identifier("tenant-a"), grant.sessionId),
        )
        assertEquals(PresignedUploadSessionStatus.READY, retryable.status)
        assertEquals(1, changedTenant.adapter.finalizeCount)
    }

    @Test
    fun `releases a failed read-only finalize claim and rejects CAS races`() {
        val failed = fixture()
        val grant = failed.service.start(command())
        failed.adapter.finalizeFailure = IllegalStateException("provider detail must not persist")

        assertThrows(IllegalStateException::class.java) {
            failed.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }

        val retryable = failed.repository.findById(Identifier("tenant-a"), grant.sessionId)
        assertEquals(PresignedUploadSessionStatus.READY, retryable?.status)
        assertEquals("IllegalStateException", retryable?.lastError)
        assertNull(retryable?.claimTime)

        val raced = fixture()
        val racedGrant = raced.service.start(command())
        raced.repository.failNextCas = true
        assertThrows(PresignedUploadStateException::class.java) {
            raced.service.complete(CompletePresignedUploadCommand(racedGrant.sessionId))
        }
        assertEquals(0, raced.adapter.finalizeCount)
    }

    @Test
    fun `repository contract rejects stale version and cross-tenant replacement`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())
        val current = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), grant.sessionId))

        assertFalse(
            fixture.repository.compareAndSet(
                current.tenantId,
                current.id,
                99,
                sessionCopy(current, version = 100),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            fixture.repository.compareAndSet(
                current.tenantId,
                current.id,
                current.version,
                sessionCopy(current, tenantId = Identifier("tenant-b"), version = current.version + 1),
            )
        }
    }

    @Test
    fun `expires durable authority without asking storage to trust a late client`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())
        fixture.clock.current = Instant.ofEpochMilli(NOW).plus(Duration.ofMinutes(21))

        assertThrows(PresignedUploadStateException::class.java) {
            fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }

        val durable = fixture.repository.findById(Identifier("tenant-a"), grant.sessionId)
        assertEquals(PresignedUploadSessionStatus.EXPIRED, durable?.status)
        assertEquals(0, fixture.adapter.finalizeCount)
    }

    @Test
    fun `status is location free and cancellation is owner scoped and cleanup waits for grant expiry`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())

        val visible = fixture.service.inspect(InspectPresignedUploadCommand(grant.sessionId))
        val cancelled = fixture.service.cancel(CancelPresignedUploadCommand(grant.sessionId))
        val early = PresignedUploadCleanupService(
            fixture.repository,
            fixture.adapter,
            fixture.clock,
        ).cleanup()

        assertEquals(PresignedUploadSessionStatus.READY, visible.status)
        assertEquals(PresignedUploadSessionStatus.CANCELLED, cancelled.status)
        assertFalse(visible.javaClass.methods.any { it.name.contains("Location") || it.name.contains("Header") })
        assertEquals(0, early.discovered)
        assertEquals(0, fixture.adapter.cleanupCount)

        fixture.clock.current = Instant.ofEpochMilli(grant.expiresAt)
        val due = PresignedUploadCleanupService(
            fixture.repository,
            fixture.adapter,
            fixture.clock,
        ).cleanup()
        val durable = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), grant.sessionId))
        assertEquals(1, due.succeeded)
        assertEquals(1, fixture.adapter.cleanupCount)
        assertEquals(grant.expiresAt, durable.cleanupTime)
        assertNull(durable.finalization)
    }

    @Test
    fun `recovery clears only an expired finalize lease with token aware CAS`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())
        val ready = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), grant.sessionId))
        val token = "sha256:${"a".repeat(64)}"
        val claimed = copyPresignedUploadSession(
            source = ready,
            status = PresignedUploadSessionStatus.FINALIZING,
            version = 1,
            claimTime = NOW,
            claimToken = token,
            claimExpiresAt = NOW + 100,
            updatedTime = NOW,
        )
        assertTrue(fixture.repository.compareAndSet(ready.tenantId, ready.id, 0, null, claimed))
        fixture.clock.current = Instant.ofEpochMilli(NOW + 100)

        val result = PresignedUploadRecoveryService(fixture.repository, fixture.clock).recover()
        val recovered = requireNotNull(fixture.repository.findById(ready.tenantId, ready.id))

        assertEquals(1, result.succeeded)
        assertEquals(PresignedUploadSessionStatus.READY, recovered.status)
        assertEquals(2L, recovered.version)
        assertNull(recovered.claimToken)
        assertNull(recovered.claimExpiresAt)
        assertEquals("PresignedUploadClaimLeaseExpired", recovered.lastError)
    }

    @Test
    fun `clock rollback fails closed before status or finalization mutation`() {
        val fixture = fixture()
        val grant = fixture.service.start(command())
        fixture.clock.current = Instant.ofEpochMilli(NOW - 1)

        assertThrows(PresignedUploadStateException::class.java) {
            fixture.service.inspect(InspectPresignedUploadCommand(grant.sessionId))
        }
        assertThrows(PresignedUploadStateException::class.java) {
            fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }

        val durable = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), grant.sessionId))
        assertEquals(PresignedUploadSessionStatus.READY, durable.status)
        assertEquals(0, fixture.adapter.finalizeCount)
    }

    @Test
    fun `unknown create and completion commit outcomes reconcile durable state`() {
        val transaction = OutcomeUnknownTransaction()
        val fixture = fixture(transaction)
        transaction.reset(throwOnCall = 2)

        val created = fixture.service.startWithCallerKey("unknown-create", command())

        assertFalse(created.created)
        assertEquals(1, fixture.repository.size())
        assertEquals(1, fixture.adapter.reissueCount)

        transaction.reset(throwOnCall = 3)
        val completed = fixture.service.complete(CompletePresignedUploadCommand(created.sessionId))
        val durable = requireNotNull(fixture.repository.findById(Identifier("tenant-a"), created.sessionId))

        assertEquals("revision-1", completed.finalization.revision)
        assertEquals(PresignedUploadSessionStatus.COMPLETED, durable.status)
        assertEquals(1, fixture.adapter.finalizeCount)
    }

    @Test
    fun `rejects an ambient application transaction before signing or finalizing`() {
        val transaction = StateAwareTransaction()
        val fixture = fixture(transaction)
        transaction.active = true

        assertThrows(ApplicationTransactionNestingException::class.java) {
            fixture.service.start(command())
        }
        assertEquals(0, fixture.adapter.createCount)

        transaction.active = false
        val grant = fixture.service.start(command())
        transaction.active = true
        assertThrows(ApplicationTransactionNestingException::class.java) {
            fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
        }
        assertThrows(ApplicationTransactionNestingException::class.java) {
            PresignedUploadRecoveryService(fixture.repository, fixture.clock, transaction).recover()
        }
        assertThrows(ApplicationTransactionNestingException::class.java) {
            PresignedUploadCleanupService(
                fixture.repository,
                fixture.adapter,
                fixture.clock,
                transaction,
            ).cleanup()
        }
        assertEquals(0, fixture.adapter.finalizeCount)
        assertEquals(0, fixture.adapter.cleanupCount)
    }

    @Test
    fun `rejects provider finalization whose tenant binding or staging authority drifted`() {
        val mutations = listOf<(PresignedUploadFinalizeRequest, PresignedUploadFinalization) -> PresignedUploadFinalization>(
            { _, base -> finalization(base, tenantId = Identifier("tenant-b")) },
            { _, base -> finalization(base, bindingId = Identifier("other-binding")) },
            { _, base ->
                finalization(
                    base,
                    sourceLocation = StorageObjectLocation("test", "objects/tenant/other-session"),
                )
            },
            { request, base -> finalization(base, storedLocation = request.location) },
        )
        mutations.forEach { mutation ->
            val fixture = fixture()
            val grant = fixture.service.start(command())
            fixture.adapter.finalizationMutation = mutation

            assertThrows(IllegalArgumentException::class.java) {
                fixture.service.complete(CompletePresignedUploadCommand(grant.sessionId))
            }

            val durable = fixture.repository.findById(Identifier("tenant-a"), grant.sessionId)
            assertEquals(PresignedUploadSessionStatus.READY, durable?.status)
            assertNull(durable?.finalLocation)
        }
    }

    private fun fixture(
        transaction: ApplicationTransaction = DirectPresignedUploadApplicationTransaction,
    ): Fixture {
        val tenantProvider = MutableTenantProvider(Identifier("tenant-a"))
        val userRealm = MutableUserRealm(UserIdentity(Identifier("owner-a")))
        val adapter = FakePresignedStorageAdapter(NOW + Duration.ofMinutes(5).toMillis())
        val repository = InMemoryPresignedUploadSessionRepository()
        val clock = MutableClock(Instant.ofEpochMilli(NOW))
        val authorization = MutableAuthorizationProvider()
        val service = PresignedUploadService(
            tenantProvider,
            userRealm,
            authorization,
            adapter,
            repository,
            object : IdentifierGenerator {
                override fun nextId(): Identifier = Identifier("session-1")
            },
            clock,
            transaction = transaction,
        )
        return Fixture(service, tenantProvider, userRealm, authorization, adapter, repository, clock)
    }

    private fun command() = StartPresignedUploadCommand(
        fileName = "contract.txt",
        contentLength = 7,
        contentType = "text/plain",
        contentHash = CONTENT_HASH,
        checksum = CHECKSUM,
        metadata = mapOf("business" to "legal"),
        grantDuration = Duration.ofMinutes(5),
    )

    private class MutableTenantProvider(var tenantId: Identifier) : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(tenantId)
    }

    private class MutableUserRealm(var user: UserIdentity?) : UserRealmProvider {
        override fun currentUser(): UserIdentity? = user
        override fun findUser(userId: Identifier): UserIdentity? = user?.takeIf { it.id == userId }
    }

    private class MutableAuthorizationProvider(var allowed: Boolean = true) : AuthorizationProvider {
        override fun authorize(request: ai.icen.fw.spi.authorization.AuthorizationRequest) =
            AuthorizationDecision(allowed)
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock =
            if (zone == ZoneOffset.UTC) this else Clock.fixed(current, zone)

        override fun instant(): Instant = current
    }

    private class OutcomeUnknownTransaction : ApplicationTransaction {
        private var calls: Int = 0
        private var throwOnCall: Int = Int.MAX_VALUE

        fun reset(throwOnCall: Int) {
            calls = 0
            this.throwOnCall = throwOnCall
        }

        override fun <T> execute(action: () -> T): T {
            calls += 1
            val result = action()
            if (calls == throwOnCall) {
                throw ApplicationTransactionOutcomeUnknownException(SQLException("commit acknowledgement lost"))
            }
            return result
        }
    }

    private class StateAwareTransaction : ApplicationTransaction, ApplicationTransactionState {
        var active: Boolean = false

        override fun <T> execute(action: () -> T): T = action()

        override fun isTransactionActive(): Boolean = active
    }

    private class FakePresignedStorageAdapter(expiresAt: Long) : PresignedUploadStorageAdapter {
        var grant = PresignedUploadGrant(
            StorageObjectLocation("test", "objects/tenant/session-1"),
            URI.create("https://storage.example/objects/tenant/session-1?signature=opaque"),
            mapOf("Content-Type" to "text/plain"),
            expiresAt,
        )
        var finalizeRequest: PresignedUploadFinalizeRequest? = null
        var finalizeFailure: Throwable? = null
        var finalizationMutation:
            ((PresignedUploadFinalizeRequest, PresignedUploadFinalization) -> PresignedUploadFinalization)? = null
        var createCount = 0
        var reissueCount = 0
        var finalizeCount = 0
        var cleanupCount = 0
        var clockToAdvance: MutableClock? = null
        var createLatencyMillis: Long = 0
        var afterCreate: (() -> Unit)? = null
        var afterFinalize: (() -> Unit)? = null

        override fun createUploadGrant(request: PresignedUploadGrantRequest): PresignedUploadGrant {
            createCount += 1
            clockToAdvance?.let { mutableClock ->
                mutableClock.current = mutableClock.current.plusMillis(createLatencyMillis)
                grant = PresignedUploadGrant(
                    grant.location,
                    grant.uploadUri,
                    grant.requiredHeaders,
                    Math.addExact(mutableClock.millis(), request.expiresIn.toMillis()),
                )
            }
            return grant.also { afterCreate?.invoke() }
        }

        override fun reissueUploadGrant(request: PresignedUploadReissueRequest): PresignedUploadGrant {
            reissueCount += 1
            return PresignedUploadGrant(
                request.location,
                grant.uploadUri,
                request.requiredHeaders,
                request.expiresAt,
            )
        }

        override fun finalizeUpload(request: PresignedUploadFinalizeRequest): PresignedUploadFinalization {
            finalizeCount += 1
            finalizeRequest = request
            finalizeFailure?.let { throw it }
            val base = PresignedUploadFinalization(
                request.tenantId,
                request.bindingId,
                request.location,
                StoredObject(
                    StorageObjectLocation("test", "bound/${request.bindingId.value}/version-1"),
                    request.contentLength,
                    request.contentType,
                    request.contentHash,
                ),
                "revision-1",
                request.checksum,
                request.metadata,
            )
            return (finalizationMutation?.invoke(request, base) ?: base).also { afterFinalize?.invoke() }
        }

        override fun cleanupUpload(request: PresignedUploadCleanupRequest) {
            cleanupCount += 1
        }
    }

    private class InMemoryPresignedUploadSessionRepository : PresignedUploadSessionRepository {
        private val sessions = ConcurrentHashMap<String, PresignedUploadSession>()
        var failNextCas: Boolean = false

        @Synchronized
        override fun create(session: PresignedUploadSession): Boolean {
            if (
                sessions.values.any {
                    (
                        it.tenantId == session.tenantId &&
                            it.ownerId == session.ownerId &&
                            it.idempotencyKeyDigest == session.idempotencyKeyDigest
                        ) || it.stagingLocation == session.stagingLocation
                }
            ) return false
            return sessions.putIfAbsent(key(session.tenantId, session.id), session) == null
        }

        override fun findById(tenantId: Identifier, sessionId: Identifier): PresignedUploadSession? =
            sessions[key(tenantId, sessionId)]

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): PresignedUploadSession? = findById(tenantId, sessionId)?.takeIf { it.ownerId == ownerId }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKeyDigest: String,
        ): PresignedUploadSession? = sessions.values.singleOrNull {
            it.tenantId == tenantId &&
                it.ownerId == ownerId &&
                it.idempotencyKeyDigest == idempotencyKeyDigest
        }

        override fun findRecoveryCandidates(now: Long, limit: Int): List<PresignedUploadSession> =
            sessions.values
                .filter {
                    it.status == PresignedUploadSessionStatus.FINALIZING &&
                        requireNotNull(it.claimExpiresAt) <= now &&
                        it.sessionExpiresAt > now
                }
                .sortedWith(compareBy(PresignedUploadSession::claimExpiresAt, { it.id.value }))
                .take(limit)

        override fun findCleanupCandidates(now: Long, limit: Int): List<PresignedUploadSession> =
            sessions.values
                .filter {
                    it.cleanupTime == null &&
                        it.grantExpiresAt <= now &&
                        (
                            it.status == PresignedUploadSessionStatus.CANCELLED ||
                                it.status == PresignedUploadSessionStatus.EXPIRED ||
                                (
                                    it.status in setOf(
                                        PresignedUploadSessionStatus.READY,
                                        PresignedUploadSessionStatus.FINALIZING,
                                    ) && it.sessionExpiresAt <= now
                                    )
                            )
                }
                .sortedWith(
                    compareBy(
                        PresignedUploadSession::updatedTime,
                        PresignedUploadSession::grantExpiresAt,
                        { it.id.value },
                    ),
                )
                .take(limit)

        @Synchronized
        override fun compareAndSet(
            tenantId: Identifier,
            sessionId: Identifier,
            expectedVersion: Long,
            replacement: PresignedUploadSession,
        ): Boolean = compareAndSet(tenantId, sessionId, expectedVersion, null, replacement)

        @Synchronized
        override fun compareAndSet(
            tenantId: Identifier,
            sessionId: Identifier,
            expectedVersion: Long,
            expectedClaimToken: String?,
            replacement: PresignedUploadSession,
        ): Boolean {
            require(replacement.tenantId == tenantId && replacement.id == sessionId) {
                "CAS replacement must preserve tenant and session identifiers."
            }
            require(replacement.version == expectedVersion + 1) {
                "CAS replacement version must advance exactly once."
            }
            if (failNextCas) {
                failNextCas = false
                return false
            }
            val key = key(tenantId, sessionId)
            val current = sessions[key] ?: return false
            if (current.version != expectedVersion || current.claimToken != expectedClaimToken) return false
            if (
                replacement.finalLocation != null &&
                sessions.values.any { it.id != sessionId && it.finalLocation == replacement.finalLocation }
            ) return false
            sessions[key] = replacement
            return true
        }

        fun size(): Int = sessions.size

        private fun key(tenantId: Identifier, sessionId: Identifier): String =
            "${tenantId.value}\u0000${sessionId.value}"
    }

    private fun sessionCopy(
        source: PresignedUploadSession,
        tenantId: Identifier = source.tenantId,
        version: Long = source.version,
    ) = PresignedUploadSession(
        source.id,
        tenantId,
        source.ownerId,
        source.fileName,
        source.contentLength,
        source.contentType,
        source.contentHash,
        source.checksum,
        source.metadata,
        source.storageLocation,
        source.grantExpiresAt,
        source.sessionExpiresAt,
        source.status,
        version,
        source.claimTime,
        source.finalization,
        source.lastError,
        source.createdTime,
        source.updatedTime,
    )

    private fun finalization(
        base: PresignedUploadFinalization,
        tenantId: Identifier = base.tenantId,
        bindingId: Identifier = base.bindingId,
        sourceLocation: StorageObjectLocation = base.sourceLocation,
        storedLocation: StorageObjectLocation = base.storedObject.location,
    ) = PresignedUploadFinalization(
        tenantId,
        bindingId,
        sourceLocation,
        StoredObject(
            storedLocation,
            base.storedObject.contentLength,
            base.storedObject.contentType,
            base.storedObject.contentHash,
        ),
        base.revision,
        base.checksum,
        base.metadata,
    )

    private data class Fixture(
        val service: PresignedUploadService,
        val tenantProvider: MutableTenantProvider,
        val userRealm: MutableUserRealm,
        val authorization: MutableAuthorizationProvider,
        val adapter: FakePresignedStorageAdapter,
        val repository: InMemoryPresignedUploadSessionRepository,
        val clock: MutableClock,
    )

    private companion object {
        const val NOW = 1_000_000L
        const val CONTENT_HASH = "sha256:239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5"
        val CHECKSUM = StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g==")
    }
}
