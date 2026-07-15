package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

private const val MANDATORY_FILTER_DIGEST_FORMAT = "flowweft.mandatory-filter.v1"
private const val BACKEND_NATIVE_SCOPE_DIGEST_FORMAT = "flowweft.backend-native-scope.v1"
private const val MAX_FILTER_VALUE_CODE_POINTS = 1_024
private const val MAX_FILTER_CANONICAL_PAYLOAD_BYTES = 4 * 1_024 * 1_024
private const val MAX_FILTER_TOTAL_VALUES =
    RetrievalContractLimits.MAX_CLAIMS * RetrievalContractLimits.MAX_CLAIM_VALUES

/** Operators with exact, case-sensitive scalar-string semantics. */
enum class RequiredFilterOperator {
    EQUALS,
    IN,
}

/**
 * One mandatory filter clause. Values are preserved exactly and are never case-folded,
 * normalized, ignored, or truncated. An EQUALS clause has exactly one value.
 */
class RequiredFilterClause private constructor(
    val fieldId: String,
    val operator: RequiredFilterOperator,
    values: Collection<String>,
) {
    val values: List<String> = immutableRetrievalList(
        immutableRetrievalList(
            values,
            RetrievalContractLimits.MAX_CLAIM_VALUES,
            "Mandatory filter clause contains too many values.",
        ).sorted(),
    )

    init {
        requireRetrievalText(
            fieldId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Mandatory filter field identifier is invalid.",
        )
        require(this.values.isNotEmpty()) { "Mandatory filter clause values must not be empty." }
        require(this.values.size <= RetrievalContractLimits.MAX_CLAIM_VALUES) {
            "Mandatory filter clause contains too many values."
        }
        require(this.values.toSet().size == this.values.size) {
            "Mandatory filter clause values must be unique."
        }
        this.values.forEach { value -> requireExactFilterScalar(value) }
        if (operator == RequiredFilterOperator.EQUALS) {
            require(this.values.size == 1) { "An EQUALS filter clause requires exactly one value." }
        }
    }

    companion object {
        @JvmStatic
        fun create(
            fieldId: String,
            operator: RequiredFilterOperator,
            values: Collection<String>,
        ): RequiredFilterClause = RequiredFilterClause(fieldId, operator, values)

        @JvmStatic
        fun equalTo(fieldId: String, value: String): RequiredFilterClause =
            RequiredFilterClause(fieldId, RequiredFilterOperator.EQUALS, listOf(value))

        @JvmStatic
        fun inValues(fieldId: String, values: Collection<String>): RequiredFilterClause =
            RequiredFilterClause(fieldId, RequiredFilterOperator.IN, values)
    }
}

/**
 * A non-empty mandatory filter whose top-level logical operation is always AND.
 *
 * Clause order and IN-value order have no semantic meaning. The exposed collections use
 * canonical case-sensitive ordering, and [digest] is the lower-case SHA-256 of a versioned,
 * length-prefixed UTF-8 representation of the complete filter.
 */
class MandatoryFilter private constructor(
    val schemaId: String,
    val schemaRevision: String,
    clauses: Collection<RequiredFilterClause>,
) {
    val clauses: List<RequiredFilterClause> =
        immutableRetrievalList(
            immutableRetrievalList(
                clauses,
                RetrievalContractLimits.MAX_CLAIMS,
                "Mandatory filter contains too many clauses.",
            ).sortedBy { clause -> clause.fieldId },
        )
    val canonicalPayloadBytes: Int
    val digest: String

    init {
        requireRetrievalText(
            schemaId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Mandatory filter schema identifier is invalid.",
        )
        requireRetrievalText(
            schemaRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Mandatory filter schema revision is invalid.",
        )
        require(this.clauses.isNotEmpty()) { "Mandatory filter clauses must not be empty." }
        require(this.clauses.size <= RetrievalContractLimits.MAX_CLAIMS) {
            "Mandatory filter contains too many clauses."
        }
        require(this.clauses.map { clause -> clause.fieldId }.toSet().size == this.clauses.size) {
            "A mandatory filter field must not appear more than once."
        }

        var totalValues = 0
        this.clauses.forEach { clause -> totalValues += clause.values.size }
        require(totalValues <= MAX_FILTER_TOTAL_VALUES) { "Mandatory filter contains too many total values." }

        canonicalPayloadBytes = mandatoryFilterCanonicalPayloadSize(schemaId, schemaRevision, this.clauses)
        require(canonicalPayloadBytes <= MAX_FILTER_CANONICAL_PAYLOAD_BYTES) {
            "Mandatory filter canonical payload is too large."
        }
        val canonicalPayload = mandatoryFilterCanonicalPayload(schemaId, schemaRevision, this.clauses)
        check(canonicalPayload.size == canonicalPayloadBytes) { "Mandatory filter canonical size is inconsistent." }
        digest = sha256Hex(canonicalPayload)
    }

    class Builder private constructor(
        private val schemaId: String,
        private val schemaRevision: String,
    ) {
        private val clauses = ArrayList<RequiredFilterClause>()

        fun addClause(clause: RequiredFilterClause): Builder {
            require(clauses.none { existing -> existing.fieldId == clause.fieldId }) {
                "A mandatory filter field must not appear more than once."
            }
            clauses.add(clause)
            return this
        }

        fun addEquals(fieldId: String, value: String): Builder =
            addClause(RequiredFilterClause.equalTo(fieldId, value))

        fun addIn(fieldId: String, values: Collection<String>): Builder =
            addClause(RequiredFilterClause.inValues(fieldId, values))

        fun build(): MandatoryFilter = MandatoryFilter(schemaId, schemaRevision, clauses)

        companion object {
            @JvmStatic
            fun create(schemaId: String, schemaRevision: String): Builder = Builder(schemaId, schemaRevision)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            schemaId: String,
            schemaRevision: String,
            clauses: Collection<RequiredFilterClause>,
        ): MandatoryFilter = MandatoryFilter(schemaId, schemaRevision, clauses)

        @JvmStatic
        fun builder(schemaId: String, schemaRevision: String): Builder =
            Builder.create(schemaId, schemaRevision)
    }
}

/**
 * Exact provider capability for translating a [MandatoryFilter] before candidate selection.
 *
 * Adapters must call [requireSupports] before any network operation. Failure means the complete
 * filter cannot be translated safely; callers must not drop clauses, values, or operators and
     * must not retry with an unfiltered request. [maxPayloadBytes] applies to FlowWeft's canonical
 * filter payload, so adapters must choose a lower limit when their wire encoding can expand it.
 */
class MandatoryFilterCapability private constructor(
    val schemaId: String,
    val schemaRevision: String,
    supportedFields: Map<String, Collection<RequiredFilterOperator>>,
    val maxClauses: Int,
    val maxValuesPerClause: Int,
    val maxTotalValues: Int,
    val maxPayloadBytes: Int,
) {
    val supportedFields: Map<String, Set<RequiredFilterOperator>> =
        immutableOperatorSupportMap(supportedFields)

    init {
        requireRetrievalText(
            schemaId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Mandatory filter capability schema identifier is invalid.",
        )
        requireRetrievalText(
            schemaRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Mandatory filter capability schema revision is invalid.",
        )
        require(this.supportedFields.isNotEmpty()) {
            "Mandatory filter capability must support at least one field."
        }
        require(this.supportedFields.size <= RetrievalContractLimits.MAX_CLAIMS) {
            "Mandatory filter capability supports too many fields."
        }
        this.supportedFields.forEach { (fieldId, operators) ->
            requireRetrievalText(
                fieldId,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Mandatory filter capability field identifier is invalid.",
            )
            require(operators.isNotEmpty()) {
                "Mandatory filter capability field must support at least one operator."
            }
        }
        require(maxClauses in 1..RetrievalContractLimits.MAX_CLAIMS) {
            "Mandatory filter capability clause limit is invalid."
        }
        require(maxValuesPerClause in 1..RetrievalContractLimits.MAX_CLAIM_VALUES) {
            "Mandatory filter capability per-clause value limit is invalid."
        }
        require(maxTotalValues in 1..MAX_FILTER_TOTAL_VALUES) {
            "Mandatory filter capability total value limit is invalid."
        }
        require(maxPayloadBytes in 1..MAX_FILTER_CANONICAL_PAYLOAD_BYTES) {
            "Mandatory filter capability payload limit is invalid."
        }
    }

    /** Rejects every incomplete or lossy translation before an adapter performs network I/O. */
    fun requireSupports(filter: MandatoryFilter) {
        require(filter.schemaId == schemaId && filter.schemaRevision == schemaRevision) {
            "Mandatory filter schema is not supported by the provider capability."
        }
        require(filter.clauses.size <= maxClauses) {
            "Mandatory filter exceeds the provider clause limit."
        }

        var totalValues = 0
        filter.clauses.forEach { clause ->
            val supportedOperators = supportedFields[clause.fieldId]
                ?: throw IllegalArgumentException("Mandatory filter contains an unsupported field.")
            require(clause.operator in supportedOperators) {
                "Mandatory filter contains an unsupported operator."
            }
            require(clause.values.size <= maxValuesPerClause) {
                "Mandatory filter clause exceeds the provider value limit."
            }
            totalValues += clause.values.size
        }

        require(totalValues <= maxTotalValues) {
            "Mandatory filter exceeds the provider total value limit."
        }
        require(filter.canonicalPayloadBytes <= maxPayloadBytes) {
            "Mandatory filter exceeds the provider payload limit."
        }
    }

    class Builder private constructor(
        private val schemaId: String,
        private val schemaRevision: String,
    ) {
        private val supportedFields = LinkedHashMap<String, Collection<RequiredFilterOperator>>()
        private var maxClauses = -1
        private var maxValuesPerClause = -1
        private var maxTotalValues = -1
        private var maxPayloadBytes = -1

        fun supportField(
            fieldId: String,
            operators: Collection<RequiredFilterOperator>,
        ): Builder {
            requireRetrievalText(
                fieldId,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Mandatory filter capability field identifier is invalid.",
            )
            require(fieldId !in supportedFields) {
                "Mandatory filter capability field must not be declared more than once."
            }
            val snapshot = immutableRetrievalSet(operators)
            require(snapshot.isNotEmpty()) {
                "Mandatory filter capability field must support at least one operator."
            }
            supportedFields[fieldId] = snapshot
            return this
        }

        fun limits(
            maxClauses: Int,
            maxValuesPerClause: Int,
            maxTotalValues: Int,
            maxPayloadBytes: Int,
        ): Builder {
            this.maxClauses = maxClauses
            this.maxValuesPerClause = maxValuesPerClause
            this.maxTotalValues = maxTotalValues
            this.maxPayloadBytes = maxPayloadBytes
            return this
        }

        fun build(): MandatoryFilterCapability {
            require(
                maxClauses > 0 &&
                    maxValuesPerClause > 0 &&
                    maxTotalValues > 0 &&
                    maxPayloadBytes > 0,
            ) { "Mandatory filter capability limits must be configured." }
            return MandatoryFilterCapability(
                schemaId,
                schemaRevision,
                supportedFields,
                maxClauses,
                maxValuesPerClause,
                maxTotalValues,
                maxPayloadBytes,
            )
        }

        companion object {
            @JvmStatic
            fun create(schemaId: String, schemaRevision: String): Builder = Builder(schemaId, schemaRevision)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            schemaId: String,
            schemaRevision: String,
            supportedFields: Map<String, Collection<RequiredFilterOperator>>,
            maxClauses: Int,
            maxValuesPerClause: Int,
            maxTotalValues: Int,
            maxPayloadBytes: Int,
        ): MandatoryFilterCapability = MandatoryFilterCapability(
            schemaId,
            schemaRevision,
            supportedFields,
            maxClauses,
            maxValuesPerClause,
            maxTotalValues,
            maxPayloadBytes,
        )

        @JvmStatic
        fun builder(schemaId: String, schemaRevision: String): Builder =
            Builder.create(schemaId, schemaRevision)
    }
}

/**
 * Provider-specific native ACL scope. It is not transferable between provider instances.
 * Every security-domain and authority field participates in [digest].
 */
class BackendNativeScope private constructor(
    val tenantId: Identifier,
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val subjectDigest: String,
    val authorityId: String,
    val providerInstanceId: String,
    val securityDomainDigest: String,
    val scopeReference: String,
    val authorityRevision: String,
    val issuedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(tenantId, "Backend-native tenant identifier is invalid.")
        requireRetrievalIdentifier(
            authorizationRequestId,
            "Backend-native authorization request identifier is invalid.",
        )
        requireDigest(authorizationRequestDigest, "Backend-native authorization request digest is invalid.")
        requireDigest(subjectDigest, "Backend-native subject digest is invalid.")
        requireRetrievalText(
            authorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Backend-native authority identifier is invalid.",
        )
        requireRetrievalText(
            providerInstanceId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Backend-native provider instance identifier is invalid.",
        )
        requireDigest(
            securityDomainDigest,
            "Backend-native security-domain digest must be a lower-case SHA-256 digest.",
        )
        requireRetrievalText(
            scopeReference,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Backend-native scope reference is invalid.",
        )
        requireRetrievalText(
            authorityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Backend-native authority revision is invalid.",
        )
        require(issuedAtEpochMilli >= 0L && expiresAtEpochMilli > issuedAtEpochMilli) {
            "Backend-native scope validity window is invalid."
        }
        require(expiresAtEpochMilli - issuedAtEpochMilli <= RetrievalAccessPlan.MAX_ACCESS_PLAN_TTL_MILLIS) {
            "Backend-native scope lifetime is too long."
        }
        digest = sha256Hex(
            backendNativeScopeCanonicalPayload(
                tenantId,
                authorizationRequestId,
                authorizationRequestDigest,
                subjectDigest,
                authorityId,
                providerInstanceId,
                securityDomainDigest,
                scopeReference,
                authorityRevision,
                issuedAtEpochMilli,
                expiresAtEpochMilli,
            ),
        )
    }

    fun requireProviderInstance(expectedProviderInstanceId: String) {
        requireRetrievalText(
            expectedProviderInstanceId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Expected backend-native provider instance identifier is invalid.",
        )
        require(providerInstanceId == expectedProviderInstanceId) {
            "Backend-native scope is bound to another provider instance."
        }
    }

    internal fun requireValidFor(
        request: RetrievalAuthorizationRequest,
        expectedIssuedAtEpochMilli: Long,
        expectedExpiresAtEpochMilli: Long,
    ) {
        require(
            tenantId == request.tenantId &&
                authorizationRequestId == request.id &&
                authorizationRequestDigest == request.digest &&
                subjectDigest == request.subject.digest,
        ) { "Backend-native scope belongs to another tenant, request, or subject." }
        require(issuedAtEpochMilli >= request.requestedAtEpochMilli) {
            "Backend-native scope predates its authorization request."
        }
        require(issuedAtEpochMilli == expectedIssuedAtEpochMilli && expiresAtEpochMilli == expectedExpiresAtEpochMilli) {
            "Backend-native scope validity does not match its access plan."
        }
    }

    companion object {
        @JvmStatic
        fun create(
            request: RetrievalAuthorizationRequest,
            authorityId: String,
            providerInstanceId: String,
            securityDomainDigest: String,
            scopeReference: String,
            authorityRevision: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): BackendNativeScope = BackendNativeScope(
            request.tenantId,
            request.id,
            request.digest,
            request.subject.digest,
            authorityId,
            providerInstanceId,
            securityDomainDigest,
            scopeReference,
            authorityRevision,
            issuedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

private fun immutableOperatorSupportMap(
    values: Map<String, Collection<RequiredFilterOperator>>,
): Map<String, Set<RequiredFilterOperator>> {
    require(values.size <= RetrievalContractLimits.MAX_CLAIMS) {
        "Mandatory filter capability supports too many fields."
    }
    val snapshot = LinkedHashMap<String, Set<RequiredFilterOperator>>(values.size)
    values.forEach { (fieldId, operators) ->
        snapshot[fieldId] = Collections.unmodifiableSet(LinkedHashSet(operators))
    }
    return Collections.unmodifiableMap(snapshot)
}

private fun requireExactFilterScalar(value: String) {
    require(value.isNotBlank()) { "Mandatory filter value is invalid." }
    require(value.codePointCount(0, value.length) <= MAX_FILTER_VALUE_CODE_POINTS) {
        "Mandatory filter value is too long."
    }

    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(!Character.isISOControl(codePoint)) { "Mandatory filter value is invalid." }
        require(Character.getType(codePoint) != Character.FORMAT.toInt()) {
            "Mandatory filter value is invalid."
        }
        require(codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) {
            "Mandatory filter value is invalid."
        }
        require(!isUnicodeNonCharacter(codePoint)) { "Mandatory filter value is invalid." }
        offset += Character.charCount(codePoint)
    }
}

private fun mandatoryFilterCanonicalPayload(
    schemaId: String,
    schemaRevision: String,
    clauses: Collection<RequiredFilterClause>,
): ByteArray = canonicalPayload { output ->
    output.writeCanonicalString(MANDATORY_FILTER_DIGEST_FORMAT)
    output.writeCanonicalString(schemaId)
    output.writeCanonicalString(schemaRevision)
    val canonicalClauses = clauses.sortedBy { clause -> clause.fieldId }
    output.writeInt(canonicalClauses.size)
    canonicalClauses.forEach { clause ->
        output.writeCanonicalString(clause.fieldId)
        output.writeCanonicalString(clause.operator.name)
        val canonicalValues = clause.values.sorted()
        output.writeInt(canonicalValues.size)
        canonicalValues.forEach { value -> output.writeCanonicalString(value) }
    }
}

private fun mandatoryFilterCanonicalPayloadSize(
    schemaId: String,
    schemaRevision: String,
    clauses: Collection<RequiredFilterClause>,
): Int {
    var size = 0L
    fun addString(value: String) {
        val encodedSize = value.toByteArray(StandardCharsets.UTF_8).size.toLong()
        size = Math.addExact(size, Math.addExact(4L, encodedSize))
        require(size <= MAX_FILTER_CANONICAL_PAYLOAD_BYTES.toLong()) {
            "Mandatory filter canonical payload is too large."
        }
    }

    addString(MANDATORY_FILTER_DIGEST_FORMAT)
    addString(schemaId)
    addString(schemaRevision)
    size = Math.addExact(size, 4L)
    clauses.forEach { clause ->
        addString(clause.fieldId)
        addString(clause.operator.name)
        size = Math.addExact(size, 4L)
        clause.values.forEach(::addString)
    }
    return Math.toIntExact(size)
}

private fun backendNativeScopeCanonicalPayload(
    tenantId: Identifier,
    authorizationRequestId: Identifier,
    authorizationRequestDigest: String,
    subjectDigest: String,
    authorityId: String,
    providerInstanceId: String,
    securityDomainDigest: String,
    scopeReference: String,
    authorityRevision: String,
    issuedAtEpochMilli: Long,
    expiresAtEpochMilli: Long,
): ByteArray = canonicalPayload { output ->
    output.writeCanonicalString(BACKEND_NATIVE_SCOPE_DIGEST_FORMAT)
    output.writeCanonicalString(tenantId.value)
    output.writeCanonicalString(authorizationRequestId.value)
    output.writeCanonicalString(authorizationRequestDigest)
    output.writeCanonicalString(subjectDigest)
    output.writeCanonicalString(authorityId)
    output.writeCanonicalString(providerInstanceId)
    output.writeCanonicalString(securityDomainDigest)
    output.writeCanonicalString(scopeReference)
    output.writeCanonicalString(authorityRevision)
    output.writeLong(issuedAtEpochMilli)
    output.writeLong(expiresAtEpochMilli)
}

private fun canonicalPayload(writer: (DataOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    val output = DataOutputStream(bytes)
    writer(output)
    output.flush()
    return bytes.toByteArray()
}

private fun DataOutputStream.writeCanonicalString(value: String) {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
}
