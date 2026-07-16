package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetExecutionBinding
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperation
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationRepository
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationStatus
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationStoreResult
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifest
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifestRepository
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifestStoreResult
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetRequest
import ai.icen.fw.governance.runtime.GovernanceStoreCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Durable pre-plan target manifests and per-item mutation checkpoints. Transactions are local and
 * never call target resolvers, storage/index providers, authorization, workers, or reconciliation.
 */
class JdbcGovernanceDeletionTargetLedger @JvmOverloads constructor(
    dataSource: DataSource,
    configuredDialect: GovernanceJdbcDialect? = null,
) : GovernanceDeletionTargetManifestRepository, GovernanceDeletionTargetItemOperationRepository {
    private val transactions = GovernanceJdbcTransactions(dataSource, configuredDialect)

    override fun findByPreparation(
        tenantId: String,
        preparationDigest: String,
    ): GovernanceDeletionTargetManifest? {
        val tenant = GovernanceJdbcValues.id(tenantId)
        val preparation = sha256(preparationDigest, "Governance target JDBC preparation digest is invalid.")
        return jdbcBoundary {
            transactions.read { connection, _ -> loadManifest(connection, tenant, preparation, false) }
        }
    }

    override fun findExact(request: GovernanceDeletionExecutionRequest): GovernanceDeletionTargetManifest? {
        val planningRequest = GovernanceDeletionTargetRequest.of(
            request.plan.context, request.plan.assessment.assessmentDigest,
        )
        val preparationDigest = GovernanceDeletionTargetManifest.calculatePreparationDigest(
            GovernanceDeletionTargetManifest.calculatePlanningIdentityDigest(planningRequest),
            request.step.stage,
        )
        return findByPreparation(request.plan.tenantId, preparationDigest)?.also { manifest ->
            manifest.bind(request)
        }
    }

    override fun createIfAbsent(
        manifest: GovernanceDeletionTargetManifest,
    ): GovernanceDeletionTargetManifestStoreResult {
        val memento = GovernanceTargetJdbcCanonicalCodec.encodeManifest(manifest)
        val mementoDigest = GovernanceJdbcDigests.bytes(memento)
        return try {
            transactions.transaction { connection, _ ->
                val current = loadManifest(connection, manifest.tenantId, manifest.preparationDigest, true)
                if (current != null) {
                    return@transaction if (current.manifestDigest == manifest.manifestDigest) {
                        GovernanceDeletionTargetManifestStoreResult.replayed(current)
                    } else {
                        GovernanceDeletionTargetManifestStoreResult.failed(GovernanceStoreCode.CONFLICT)
                    }
                }
                insertManifest(connection, manifest, memento, mementoDigest)
                GovernanceDeletionTargetManifestStoreResult.stored(manifest)
            }
        } catch (_: GovernanceJdbcCommitOutcomeUnknownException) {
            reconcileManifestAfterUnknownCommit(manifest)
        } catch (failure: SQLException) {
            if (!failure.isGovernanceUniqueViolation()) throw GovernanceJdbcPersistenceException(failure)
            val current = findByPreparation(manifest.tenantId, manifest.preparationDigest)
            if (current?.manifestDigest == manifest.manifestDigest) {
                GovernanceDeletionTargetManifestStoreResult.replayed(current)
            } else {
                GovernanceDeletionTargetManifestStoreResult.failed(GovernanceStoreCode.CONFLICT)
            }
        }
    }

    override fun load(
        binding: GovernanceDeletionTargetExecutionBinding,
        itemBindingDigest: String,
    ): GovernanceDeletionTargetItemOperation? {
        val itemDigest = sha256(itemBindingDigest, "Governance target JDBC item binding digest is invalid.")
        val operationKeyDigest = GovernanceDeletionTargetItemOperation.calculateOperationKeyDigest(
            binding, itemDigest,
        )
        return jdbcBoundary {
            transactions.read { connection, _ ->
                loadOperation(connection, binding.tenantId, operationKeyDigest, false)?.also { operation ->
                    require(operation.binding.bindingDigest == binding.bindingDigest &&
                        operation.itemBindingDigest == itemDigest
                    ) { "Governance target JDBC operation key collision is fail-closed." }
                }
            }
        }
    }

    override fun prepare(
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult {
        require(candidate.status == GovernanceDeletionTargetItemOperationStatus.PREPARED && candidate.version == 1L) {
            "Governance target JDBC can only prepare an initial item operation."
        }
        val memento = GovernanceTargetJdbcCanonicalCodec.encodeOperation(candidate)
        val mementoDigest = GovernanceJdbcDigests.bytes(memento)
        return try {
            transactions.transaction { connection, _ ->
                val manifest = loadManifest(
                    connection,
                    candidate.binding.tenantId,
                    candidate.binding.preparationDigest,
                    true,
                )
                if (manifest == null || !manifestMatchesBinding(manifest, candidate.binding) ||
                    manifest.items.none { it.itemBindingDigest == candidate.itemBindingDigest }
                ) {
                    return@transaction GovernanceDeletionTargetItemOperationStoreResult.failed(
                        GovernanceStoreCode.CONFLICT,
                    )
                }
                val current = loadOperation(
                    connection, candidate.binding.tenantId, candidate.operationKeyDigest, true,
                )
                if (current != null) {
                    return@transaction if (current.stateDigest == candidate.stateDigest) {
                        GovernanceDeletionTargetItemOperationStoreResult.replayed(current)
                    } else {
                        GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
                    }
                }
                insertOperation(connection, candidate, memento, mementoDigest)
                GovernanceDeletionTargetItemOperationStoreResult.stored(candidate)
            }
        } catch (_: GovernanceJdbcCommitOutcomeUnknownException) {
            reconcileOperationAfterUnknownCommit(candidate)
        } catch (failure: SQLException) {
            if (!failure.isGovernanceUniqueViolation()) throw GovernanceJdbcPersistenceException(failure)
            reconcileOperation(candidate, GovernanceStoreCode.CONFLICT)
        }
    }

    override fun compareAndSet(
        expected: GovernanceDeletionTargetItemOperation,
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult {
        require(candidate.isValidSuccessorOf(expected)) {
            "Governance target JDBC item operation transition is invalid."
        }
        val memento = GovernanceTargetJdbcCanonicalCodec.encodeOperation(candidate)
        val mementoDigest = GovernanceJdbcDigests.bytes(memento)
        return try {
            transactions.transaction { connection, _ ->
                val manifest = loadManifest(
                    connection,
                    expected.binding.tenantId,
                    expected.binding.preparationDigest,
                    true,
                )
                if (manifest == null || !manifestMatchesBinding(manifest, expected.binding) ||
                    manifest.items.none { it.itemBindingDigest == expected.itemBindingDigest }
                ) {
                    return@transaction GovernanceDeletionTargetItemOperationStoreResult.failed(
                        GovernanceStoreCode.CONFLICT,
                    )
                }
                val current = loadOperation(
                    connection, expected.binding.tenantId, expected.operationKeyDigest, true,
                ) ?: return@transaction GovernanceDeletionTargetItemOperationStoreResult.failed(
                    GovernanceStoreCode.CONFLICT,
                )
                if (current.stateDigest == candidate.stateDigest) {
                    return@transaction GovernanceDeletionTargetItemOperationStoreResult.replayed(current)
                }
                if (current.version != expected.version || current.stateDigest != expected.stateDigest ||
                    current.binding.bindingDigest != expected.binding.bindingDigest
                ) {
                    return@transaction GovernanceDeletionTargetItemOperationStoreResult.failed(
                        GovernanceStoreCode.CONFLICT,
                    )
                }
                val changed = updateOperation(connection, current, candidate, memento, mementoDigest)
                if (!changed) {
                    GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
                } else {
                    GovernanceDeletionTargetItemOperationStoreResult.stored(candidate)
                }
            }
        } catch (_: GovernanceJdbcCommitOutcomeUnknownException) {
            reconcileOperationAfterUnknownCommit(candidate)
        } catch (failure: SQLException) {
            throw GovernanceJdbcPersistenceException(failure)
        }
    }

    override fun toString(): String = "JdbcGovernanceDeletionTargetLedger(<redacted>)"

    private fun insertManifest(
        connection: Connection,
        manifest: GovernanceDeletionTargetManifest,
        memento: ByteArray,
        mementoDigest: String,
    ) {
        connection.prepareStatement(
            """INSERT INTO fw_governance_deletion_target_manifest
               (id, tenant_id, preparation_digest, planning_request_digest, planning_identity_digest,
                resource_reference_digest,
                assessment_digest, stage_code, target_reference, target_reference_digest,
                target_revision, target_digest, target_binding_digest, manifest_digest,
                memento_version, manifest_memento, manifest_memento_digest, created_time, updated_time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, manifestRowId(manifest.tenantId, manifest.preparationDigest))
            statement.setString(2, manifest.tenantId)
            statement.setString(3, manifest.preparationDigest)
            statement.setString(4, manifest.planningRequestDigest)
            statement.setString(5, manifest.planningIdentityDigest)
            statement.setString(6, manifest.resourceReferenceDigest)
            statement.setString(7, manifest.assessmentDigest)
            statement.setString(8, manifest.stage.name)
            statement.setString(9, manifest.targetRef)
            statement.setString(10, targetReferenceDigest(
                manifest.tenantId, manifest.stage.name, manifest.targetRef,
            ))
            statement.setString(11, manifest.targetRevision)
            statement.setString(12, manifest.targetDigest)
            statement.setString(13, manifest.targetBindingDigest)
            statement.setString(14, manifest.manifestDigest)
            statement.setInt(15, GovernanceTargetJdbcCanonicalCodec.VERSION)
            statement.setBytes(16, memento)
            statement.setString(17, mementoDigest)
            statement.setLong(18, manifest.createdAtEpochMilli)
            statement.setLong(19, manifest.createdAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Governance target JDBC manifest insert failed." }
        }
    }

    private fun loadManifest(
        connection: Connection,
        tenantId: String,
        preparationDigest: String,
        forUpdate: Boolean,
    ): GovernanceDeletionTargetManifest? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """SELECT id, tenant_id, preparation_digest, planning_request_digest, planning_identity_digest,
                      resource_reference_digest, assessment_digest, stage_code, target_reference,
                      target_reference_digest, target_revision, target_digest, target_binding_digest,
                      manifest_digest, memento_version, manifest_memento, manifest_memento_digest,
                      OCTET_LENGTH(manifest_memento) AS manifest_memento_size, created_time, updated_time
               FROM fw_governance_deletion_target_manifest
               WHERE tenant_id = ? AND preparation_digest = ?$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, preparationDigest)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val manifest = readManifest(result)
                check(!result.next()) { "Governance target JDBC manifest identity is not unique." }
                require(manifest.tenantId == tenantId && manifest.preparationDigest == preparationDigest) {
                    "Governance target JDBC manifest row identity is invalid."
                }
                manifest
            }
        }
    }

    private fun readManifest(result: ResultSet): GovernanceDeletionTargetManifest {
        require(result.getInt("memento_version") == GovernanceTargetJdbcCanonicalCodec.VERSION) {
            "Governance target JDBC manifest memento version is unsupported."
        }
        val bytes = readMemento(result, "manifest_memento", "manifest_memento_size")
        val mementoDigest = result.getString("manifest_memento_digest")
        require(GovernanceJdbcDigests.bytes(bytes) == mementoDigest) {
            "Governance target JDBC manifest memento digest is invalid."
        }
        val manifest = GovernanceTargetJdbcCanonicalCodec.decodeManifest(
            bytes, result.getString("manifest_digest"),
        )
        require(result.getString("id") == manifestRowId(manifest.tenantId, manifest.preparationDigest) &&
            result.getString("tenant_id") == manifest.tenantId &&
            result.getString("preparation_digest") == manifest.preparationDigest &&
            result.getString("planning_request_digest") == manifest.planningRequestDigest &&
            result.getString("planning_identity_digest") == manifest.planningIdentityDigest &&
            result.getString("resource_reference_digest") == manifest.resourceReferenceDigest &&
            result.getString("assessment_digest") == manifest.assessmentDigest &&
            result.getString("stage_code") == manifest.stage.name &&
            result.getString("target_reference") == manifest.targetRef &&
            result.getString("target_reference_digest") == targetReferenceDigest(
                manifest.tenantId, manifest.stage.name, manifest.targetRef,
            ) && result.getString("target_revision") == manifest.targetRevision &&
            result.getString("target_digest") == manifest.targetDigest &&
            result.getString("target_binding_digest") == manifest.targetBindingDigest &&
            result.getLong("created_time") == manifest.createdAtEpochMilli &&
            result.getLong("updated_time") == manifest.createdAtEpochMilli
        ) { "Governance target JDBC manifest row binding is invalid." }
        return manifest
    }

    private fun insertOperation(
        connection: Connection,
        operation: GovernanceDeletionTargetItemOperation,
        memento: ByteArray,
        mementoDigest: String,
    ) {
        connection.prepareStatement(
            """INSERT INTO fw_governance_deletion_item_operation
               (id, tenant_id, operation_key_digest, plan_digest, step_digest, target_binding_digest,
                preparation_digest, manifest_digest, item_binding_digest, provider_id, provider_revision,
                operation_reference, operation_reference_digest, operation_status, operation_version,
                state_digest, memento_version, operation_memento, operation_memento_digest,
                created_time, updated_time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, operationRowId(operation.binding.tenantId, operation.operationKeyDigest))
            statement.setString(2, operation.binding.tenantId)
            statement.setString(3, operation.operationKeyDigest)
            statement.setString(4, operation.binding.planDigest)
            statement.setString(5, operation.binding.stepDigest)
            statement.setString(6, operation.binding.bindingDigest)
            statement.setString(7, operation.binding.preparationDigest)
            statement.setString(8, operation.binding.manifestDigest)
            statement.setString(9, operation.itemBindingDigest)
            statement.setString(10, operation.providerId)
            statement.setString(11, operation.providerRevision)
            statement.setString(12, operation.operationReference)
            statement.setString(13, operationReferenceDigest(
                operation.binding.tenantId,
                operation.providerId,
                operation.providerRevision,
                operation.operationReference,
            ))
            statement.setString(14, operation.status.code)
            statement.setLong(15, operation.version)
            statement.setString(16, operation.stateDigest)
            statement.setInt(17, GovernanceTargetJdbcCanonicalCodec.VERSION)
            statement.setBytes(18, memento)
            statement.setString(19, mementoDigest)
            statement.setLong(20, operation.preparedAtEpochMilli)
            statement.setLong(21, operation.updatedAtEpochMilli)
            check(statement.executeUpdate() == 1) { "Governance target JDBC item operation insert failed." }
        }
    }

    private fun updateOperation(
        connection: Connection,
        current: GovernanceDeletionTargetItemOperation,
        candidate: GovernanceDeletionTargetItemOperation,
        memento: ByteArray,
        mementoDigest: String,
    ): Boolean = connection.prepareStatement(
        """UPDATE fw_governance_deletion_item_operation
           SET operation_status = ?, operation_version = ?, state_digest = ?, memento_version = ?,
               operation_memento = ?, operation_memento_digest = ?, updated_time = ?
           WHERE tenant_id = ? AND id = ? AND operation_key_digest = ? AND plan_digest = ?
             AND preparation_digest = ? AND step_digest = ? AND target_binding_digest = ? AND manifest_digest = ?
             AND item_binding_digest = ? AND provider_id = ? AND provider_revision = ?
             AND operation_reference_digest = ? AND operation_version = ? AND state_digest = ?""".trimIndent(),
    ).use { statement ->
        statement.setString(1, candidate.status.code)
        statement.setLong(2, candidate.version)
        statement.setString(3, candidate.stateDigest)
        statement.setInt(4, GovernanceTargetJdbcCanonicalCodec.VERSION)
        statement.setBytes(5, memento)
        statement.setString(6, mementoDigest)
        statement.setLong(7, candidate.updatedAtEpochMilli)
        statement.setString(8, current.binding.tenantId)
        statement.setString(9, operationRowId(current.binding.tenantId, current.operationKeyDigest))
        statement.setString(10, current.operationKeyDigest)
        statement.setString(11, current.binding.planDigest)
        statement.setString(12, current.binding.preparationDigest)
        statement.setString(13, current.binding.stepDigest)
        statement.setString(14, current.binding.bindingDigest)
        statement.setString(15, current.binding.manifestDigest)
        statement.setString(16, current.itemBindingDigest)
        statement.setString(17, current.providerId)
        statement.setString(18, current.providerRevision)
        statement.setString(19, operationReferenceDigest(
            current.binding.tenantId,
            current.providerId,
            current.providerRevision,
            current.operationReference,
        ))
        statement.setLong(20, current.version)
        statement.setString(21, current.stateDigest)
        statement.executeUpdate() == 1
    }

    private fun loadOperation(
        connection: Connection,
        tenantId: String,
        operationKeyDigest: String,
        forUpdate: Boolean,
    ): GovernanceDeletionTargetItemOperation? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """SELECT id, tenant_id, operation_key_digest, preparation_digest, plan_digest, step_digest,
                      target_binding_digest, manifest_digest, item_binding_digest, provider_id,
                      provider_revision, operation_reference, operation_reference_digest,
                      operation_status, operation_version, state_digest, memento_version,
                      operation_memento, operation_memento_digest,
                      OCTET_LENGTH(operation_memento) AS operation_memento_size,
                      created_time, updated_time
               FROM fw_governance_deletion_item_operation
               WHERE tenant_id = ? AND operation_key_digest = ?$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, operationKeyDigest)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val operation = readOperation(result)
                check(!result.next()) { "Governance target JDBC operation identity is not unique." }
                require(operation.binding.tenantId == tenantId &&
                    operation.operationKeyDigest == operationKeyDigest
                ) { "Governance target JDBC operation row identity is invalid." }
                operation
            }
        }
    }

    private fun readOperation(result: ResultSet): GovernanceDeletionTargetItemOperation {
        require(result.getInt("memento_version") == GovernanceTargetJdbcCanonicalCodec.VERSION) {
            "Governance target JDBC operation memento version is unsupported."
        }
        val bytes = readMemento(result, "operation_memento", "operation_memento_size")
        val mementoDigest = result.getString("operation_memento_digest")
        require(GovernanceJdbcDigests.bytes(bytes) == mementoDigest) {
            "Governance target JDBC operation memento digest is invalid."
        }
        val operation = GovernanceTargetJdbcCanonicalCodec.decodeOperation(
            bytes, result.getString("state_digest"),
        )
        require(result.getString("id") == operationRowId(
            operation.binding.tenantId, operation.operationKeyDigest,
        ) && result.getString("tenant_id") == operation.binding.tenantId &&
            result.getString("operation_key_digest") == operation.operationKeyDigest &&
            result.getString("preparation_digest") == operation.binding.preparationDigest &&
            result.getString("plan_digest") == operation.binding.planDigest &&
            result.getString("step_digest") == operation.binding.stepDigest &&
            result.getString("target_binding_digest") == operation.binding.bindingDigest &&
            result.getString("manifest_digest") == operation.binding.manifestDigest &&
            result.getString("item_binding_digest") == operation.itemBindingDigest &&
            result.getString("provider_id") == operation.providerId &&
            result.getString("provider_revision") == operation.providerRevision &&
            result.getString("operation_reference") == operation.operationReference &&
            result.getString("operation_reference_digest") == operationReferenceDigest(
                operation.binding.tenantId,
                operation.providerId,
                operation.providerRevision,
                operation.operationReference,
            ) && result.getString("operation_status") == operation.status.code &&
            result.getLong("operation_version") == operation.version &&
            result.getLong("created_time") == operation.preparedAtEpochMilli &&
            result.getLong("updated_time") == operation.updatedAtEpochMilli
        ) { "Governance target JDBC operation row binding is invalid." }
        return operation
    }

    private fun reconcileOperation(
        candidate: GovernanceDeletionTargetItemOperation,
        absentCode: GovernanceStoreCode,
    ): GovernanceDeletionTargetItemOperationStoreResult {
        val current = load(candidate.binding, candidate.itemBindingDigest)
        return if (current?.stateDigest == candidate.stateDigest) {
            GovernanceDeletionTargetItemOperationStoreResult.replayed(current)
        } else {
            GovernanceDeletionTargetItemOperationStoreResult.failed(absentCode)
        }
    }

    /**
     * A lost commit acknowledgement is never converted to a retryable failure. Only a complete,
     * canonical reread of the exact candidate proves an idempotent replay; an absent, conflicting,
     * malformed, or temporarily unreadable row leaves the original write outcome unknown.
     */
    private fun reconcileManifestAfterUnknownCommit(
        candidate: GovernanceDeletionTargetManifest,
    ): GovernanceDeletionTargetManifestStoreResult = try {
        val current = findByPreparation(candidate.tenantId, candidate.preparationDigest)
        if (current?.manifestDigest == candidate.manifestDigest) {
            GovernanceDeletionTargetManifestStoreResult.replayed(current)
        } else {
            GovernanceDeletionTargetManifestStoreResult.failed(GovernanceStoreCode.OUTCOME_UNKNOWN)
        }
    } catch (_: RuntimeException) {
        GovernanceDeletionTargetManifestStoreResult.failed(GovernanceStoreCode.OUTCOME_UNKNOWN)
    }

    private fun reconcileOperationAfterUnknownCommit(
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult = try {
        reconcileOperation(candidate, GovernanceStoreCode.OUTCOME_UNKNOWN)
    } catch (_: RuntimeException) {
        GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.OUTCOME_UNKNOWN)
    }

    private fun manifestMatchesBinding(
        manifest: GovernanceDeletionTargetManifest,
        binding: GovernanceDeletionTargetExecutionBinding,
    ): Boolean = manifest.tenantId == binding.tenantId &&
        manifest.preparationDigest == binding.preparationDigest &&
        manifest.planningRequestDigest == binding.planningRequestDigest &&
        manifest.planningIdentityDigest == binding.planningIdentityDigest &&
        manifest.stage == binding.stage &&
        manifest.targetRef == binding.targetRef &&
        manifest.targetRevision == binding.targetRevision &&
        manifest.targetDigest == binding.targetDigest &&
        manifest.manifestDigest == binding.manifestDigest

    private fun readMemento(result: ResultSet, column: String, sizeColumn: String): ByteArray {
        val expectedSize = result.getLong(sizeColumn)
        require(expectedSize in 1L..GovernanceTargetJdbcCanonicalCodec.MAX_MEMENTO_BYTES.toLong()) {
            "Governance target JDBC memento size is invalid."
        }
        val output = ByteArrayOutputStream(expectedSize.toInt())
        try {
            result.getBinaryStream(column).use { input ->
                requireNotNull(input) { "Governance target JDBC memento is missing." }
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= GovernanceTargetJdbcCanonicalCodec.MAX_MEMENTO_BYTES) {
                        "Governance target JDBC memento is too large."
                    }
                    output.write(buffer, 0, read)
                }
            }
        } catch (failure: IOException) {
            throw GovernanceJdbcPersistenceException(
                SQLException("Governance target JDBC memento stream failed.", failure),
            )
        }
        return output.toByteArray().also { bytes ->
            require(bytes.size.toLong() == expectedSize) { "Governance target JDBC memento size changed." }
        }
    }

    private inline fun <T> jdbcBoundary(action: () -> T): T = try {
        action()
    } catch (failure: GovernanceJdbcPersistenceException) {
        throw failure
    } catch (failure: SQLException) {
        throw GovernanceJdbcPersistenceException(failure)
    }

    private fun manifestRowId(tenantId: String, preparationDigest: String): String =
        GovernanceJdbcDigests.rowId("flowweft-governance-target-manifest-row-v1", tenantId, preparationDigest)

    private fun operationRowId(tenantId: String, operationKeyDigest: String): String =
        GovernanceJdbcDigests.rowId("flowweft-governance-target-operation-row-v1", tenantId, operationKeyDigest)

    private fun targetReferenceDigest(tenantId: String, stage: String, targetRef: String): String =
        GovernanceJdbcDigests.digest(
            "flowweft-governance-target-reference-v1", tenantId, stage, targetRef,
        )

    private fun operationReferenceDigest(
        tenantId: String,
        providerId: String,
        providerRevision: String,
        operationReference: String,
    ): String =
        GovernanceJdbcDigests.digest(
            "flowweft-governance-operation-reference-v1",
            tenantId,
            providerId,
            providerRevision,
            operationReference,
        )

    private fun sha256(value: String, message: String): String {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { message }
        return value
    }
}
