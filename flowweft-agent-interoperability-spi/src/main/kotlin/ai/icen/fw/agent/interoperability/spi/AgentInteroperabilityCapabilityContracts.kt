package ai.icen.fw.agent.interoperability.spi

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentFailureCategory
import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemotePeerObservation
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchResult
import ai.icen.fw.agent.api.AgentRemoteProtocolResultStatus
import ai.icen.fw.agent.api.AgentRunFailure
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

/** Exact successful transport evidence from the existing hardened remote-dispatch API. */
class AgentInteroperabilityDispatchEvidence private constructor(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val result: AgentRemoteProtocolDispatchResult,
) {
    val observation: AgentRemotePeerObservation
    val evidenceDigest: String

    init {
        require(result.dispatchRequestId == dispatch.requestId &&
            result.dispatchBindingDigest == dispatch.bindingDigest
        ) { "Interoperability result belongs to another remote dispatch." }
        require(result.status == AgentRemoteProtocolResultStatus.SUCCEEDED) {
            "Interoperability capability evidence requires a successful remote dispatch."
        }
        observation = requireNotNull(result.observation) {
            "Interoperability capability evidence requires a peer observation."
        }
        observation.requireMatches(dispatch.profile)
        require(result.transportReceipt.tlsVerified && dispatch.profile.maximumRedirects == 0) {
            "Interoperability capability evidence requires pinned TLS and no redirects."
        }
        evidenceDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.dispatch-evidence.v1",
        )
            .text(dispatch.requestId.value)
            .text(dispatch.bindingDigest)
            .text(result.resultId.value)
            .text(result.resultDigest)
            .text(observation.bindingDigest)
            .text(result.transportReceipt.bindingDigest)
            .finish()
    }

    fun requireInitialization() {
        require(dispatch.invocation.operation.operation == AgentRemoteOperationKind.INITIALIZE) {
            "Interoperability capability discovery must be anchored to remote initialization."
        }
    }

    override fun toString(): String =
        "AgentInteroperabilityDispatchEvidence(protocol=${dispatch.profile.protocol}, values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            dispatch: AgentRemoteProtocolDispatchRequest,
            result: AgentRemoteProtocolDispatchResult,
        ): AgentInteroperabilityDispatchEvidence = AgentInteroperabilityDispatchEvidence(dispatch, result)
    }
}

/**
 * Extension capability snapshot. Protocol, transport, credentials, tools, and trusted subject remain
 * owned by [AgentRemotePeerProfile] and [AgentRemoteProtocolDispatchRequest]; only the missing MCP
 * resource/prompt catalog digest is added here. [providerId] identifies the local capability/catalog
 * provider that vouches for this snapshot; [profile.peerId] identifies the remote peer. They are
 * intentionally distinct identities and both are digest-bound.
 */
class AgentInteroperabilityCapabilitySnapshot private constructor(
    contractVersion: String,
    val providerId: ProviderId,
    providerRevision: String,
    val profile: AgentRemotePeerProfile,
    val observation: AgentRemotePeerObservation,
    val mcpCatalog: McpCatalogSnapshot?,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val contractVersion: String = InteroperabilityContractSupport.requireText(
        contractVersion,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "Interoperability capability contract version is invalid.",
    )
    val providerRevision: String = InteroperabilityContractSupport.requireText(
        providerRevision,
        InteroperabilityContractSupport.MAX_REVISION_UTF8_BYTES,
        "Interoperability capability provider revision is invalid.",
    )
    val capabilities: List<AgentCapabilityId>
    val capabilityDigest: String

    init {
        require(this.contractVersion == AgentInteroperabilityContractVersions.V1) {
            "Unsupported interoperability capability contract version."
        }
        observation.requireMatches(profile)
        require(observation.observedAt <= observedAt && observedAt >= 0L && expiresAt > observedAt) {
            "Interoperability capability observation window is invalid."
        }
        val sortedCapabilities = profile.capabilities.sortedBy { it.value }
        capabilities = InteroperabilityContractSupport.immutableList(
            sortedCapabilities,
            InteroperabilityContractSupport.MAX_CAPABILITIES,
            "Interoperability capabilities are invalid.",
        )
        val catalogCapability = AgentInteroperabilityCapabilities.MCP_CATALOG_SNAPSHOT in profile.capabilities
        require(catalogCapability == (mcpCatalog != null)) {
            "MCP catalog capability and catalog snapshot must be present together."
        }
        mcpCatalog?.let { catalog ->
            catalog.requireCurrentFor(profile, observation, observedAt)
            require(catalog.providerRevision == this.providerRevision) {
                "MCP catalog and interoperability capability revisions differ."
            }
        }
        val digest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.capability-snapshot.v1",
        )
            .text(this.contractVersion)
            .text(providerId.value)
            .text(this.providerRevision)
            .text(profile.profileDigest)
            .text(profile.capabilityDigest)
            .text(profile.toolCatalogDigest)
            .text(observation.bindingDigest)
            .optionalText(mcpCatalog?.catalogDigest)
            .longValue(observedAt)
            .longValue(expiresAt)
            .integer(capabilities.size)
        capabilities.forEach { capability -> digest.text(capability.value) }
        capabilityDigest = digest.finish()
    }

    fun supports(capabilityId: AgentCapabilityId): Boolean = capabilityId in capabilities

    fun requireCurrentFor(
        expectedProfile: AgentRemotePeerProfile,
        expectedObservation: AgentRemotePeerObservation,
        atTime: Long,
    ) {
        require(expectedProfile.profileDigest == profile.profileDigest) {
            "Interoperability capability profile changed."
        }
        expectedObservation.requireMatches(expectedProfile)
        require(expectedObservation.bindingDigest == observation.bindingDigest) {
            "Interoperability capability observation changed."
        }
        require(atTime in observedAt until expiresAt) { "Interoperability capability snapshot is stale." }
    }

    override fun toString(): String =
        "AgentInteroperabilityCapabilitySnapshot(peerId=${profile.peerId}, capabilities=${capabilities.size}, values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            contractVersion: String,
            providerId: ProviderId,
            providerRevision: String,
            profile: AgentRemotePeerProfile,
            observation: AgentRemotePeerObservation,
            mcpCatalog: McpCatalogSnapshot?,
            observedAt: Long,
            expiresAt: Long,
        ): AgentInteroperabilityCapabilitySnapshot = AgentInteroperabilityCapabilitySnapshot(
            contractVersion,
            providerId,
            providerRevision,
            profile,
            observation,
            mcpCatalog,
            observedAt,
            expiresAt,
        )
    }
}

/** Adds the extension capability digest to an exact existing remote dispatch without changing that ABI. */
class AgentInteroperabilityDispatchBinding private constructor(
    val dispatch: AgentRemoteProtocolDispatchRequest,
    val snapshot: AgentInteroperabilityCapabilitySnapshot,
    requiredCapabilities: Collection<AgentCapabilityId>,
    val boundAt: Long,
) {
    val requiredCapabilities: List<AgentCapabilityId>
    val bindingDigest: String

    init {
        require(dispatch.profile.profileDigest == snapshot.profile.profileDigest &&
            dispatch.invocation.approvedProfileDigest == snapshot.profile.profileDigest
        ) { "Interoperability capability snapshot belongs to another remote dispatch profile." }
        snapshot.requireCurrentFor(dispatch.profile, snapshot.observation, boundAt)
        require(boundAt >= dispatch.requestedAt && boundAt < dispatch.invocation.deadlineAt) {
            "Interoperability dispatch binding is outside the remote invocation deadline."
        }
        require(snapshot.supports(dispatch.invocation.requiredCapability)) {
            "Interoperability snapshot lacks the remote dispatch capability."
        }
        val sortedRequired = requiredCapabilities.sortedBy { it.value }
        require(sortedRequired.map { it.value }.toSet().size == sortedRequired.size) {
            "Interoperability required capabilities must be unique."
        }
        require(sortedRequired.all(snapshot::supports)) {
            "Interoperability snapshot lacks a required extension capability."
        }
        this.requiredCapabilities = InteroperabilityContractSupport.immutableList(
            sortedRequired,
            InteroperabilityContractSupport.MAX_CAPABILITIES,
            "Interoperability required capabilities are invalid.",
        )
        val digest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.dispatch-binding.v1",
        )
            .text(dispatch.requestId.value)
            .text(dispatch.bindingDigest)
            .text(snapshot.capabilityDigest)
            .longValue(boundAt)
            .integer(this.requiredCapabilities.size)
        this.requiredCapabilities.forEach { capability -> digest.text(capability.value) }
        bindingDigest = digest.finish()
    }

    override fun toString(): String =
        "AgentInteroperabilityDispatchBinding(protocol=${dispatch.profile.protocol}, values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            dispatch: AgentRemoteProtocolDispatchRequest,
            snapshot: AgentInteroperabilityCapabilitySnapshot,
            requiredCapabilities: Collection<AgentCapabilityId>,
            boundAt: Long,
        ): AgentInteroperabilityDispatchBinding = AgentInteroperabilityDispatchBinding(
            dispatch,
            snapshot,
            requiredCapabilities,
            boundAt,
        )
    }
}

/** Bounded read-only query anchored to one successful existing initialization dispatch. */
class AgentInteroperabilityCapabilityRequest private constructor(
    requestId: Identifier,
    val providerId: ProviderId,
    val dispatchEvidence: AgentInteroperabilityDispatchEvidence,
    expectedCapabilityDigest: String?,
    expectedCatalogDigest: String?,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val requestId: Identifier = requestId
    val expectedCapabilityDigest: String? = expectedCapabilityDigest?.let {
        InteroperabilityContractSupport.requireSha256(
            it,
            "Expected interoperability capability digest is invalid.",
        )
    }
    val expectedCatalogDigest: String? = expectedCatalogDigest?.let {
        InteroperabilityContractSupport.requireSha256(
            it,
            "Expected interoperability catalog digest is invalid.",
        )
    }
    val requestDigest: String

    init {
        InteroperabilityContractSupport.requireOpaqueReference(
            requestId.value,
            "Interoperability capability request identifier is invalid.",
        )
        dispatchEvidence.requireInitialization()
        val invocation = dispatchEvidence.dispatch.invocation
        require(requestedAt >= dispatchEvidence.result.completedAt && requestedAt < deadlineAt &&
            deadlineAt <= invocation.reconciliationDeadlineAt
        ) { "Interoperability capability request window is invalid." }
        require(deadlineAt - requestedAt <= InteroperabilityContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Interoperability capability request duration is too large."
        }
        requestDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.capability-request.v1",
        )
            .text(requestId.value)
            .text(providerId.value)
            .text(dispatchEvidence.evidenceDigest)
            .optionalText(this.expectedCapabilityDigest)
            .optionalText(this.expectedCatalogDigest)
            .longValue(requestedAt)
            .longValue(deadlineAt)
            .finish()
    }

    fun requireCurrent(atTime: Long) {
        require(atTime in requestedAt until deadlineAt) { "Interoperability capability request is not current." }
        dispatchEvidence.dispatch.invocation.cancellationToken.cancellation()?.let {
            throw ai.icen.fw.agent.api.AgentCancellationException(it)
        }
    }

    override fun toString(): String = "AgentInteroperabilityCapabilityRequest(values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            providerId: ProviderId,
            dispatchEvidence: AgentInteroperabilityDispatchEvidence,
            expectedCapabilityDigest: String?,
            expectedCatalogDigest: String?,
            requestedAt: Long,
            deadlineAt: Long,
        ): AgentInteroperabilityCapabilityRequest = AgentInteroperabilityCapabilityRequest(
            requestId,
            providerId,
            dispatchEvidence,
            expectedCapabilityDigest,
            expectedCatalogDigest,
            requestedAt,
            deadlineAt,
        )
    }
}

enum class AgentInteroperabilityCapabilityStatus {
    AVAILABLE,
    DRIFTED,
    UNSUPPORTED,
    UNAVAILABLE,
}

class AgentInteroperabilityCapabilityResult private constructor(
    request: AgentInteroperabilityCapabilityRequest,
    val status: AgentInteroperabilityCapabilityStatus,
    val snapshot: AgentInteroperabilityCapabilitySnapshot?,
    val failure: AgentRunFailure?,
    evidenceDigest: String,
    val completedAt: Long,
) {
    val requestId: Identifier = request.requestId
    val requestDigest: String = request.requestDigest
    val evidenceDigest: String = InteroperabilityContractSupport.requireSha256(
        evidenceDigest,
        "Interoperability capability evidence digest is invalid.",
    )
    val resultDigest: String

    init {
        require(completedAt in request.requestedAt..request.deadlineAt) {
            "Interoperability capability result time is invalid."
        }
        when (status) {
            AgentInteroperabilityCapabilityStatus.AVAILABLE -> {
                val value = requireNotNull(snapshot) { "Available interoperability capabilities require a snapshot." }
                require(failure == null) { "Available interoperability capabilities cannot include a failure." }
                require(value.providerId == request.providerId) {
                    "Interoperability capability snapshot came from another provider."
                }
                value.requireCurrentFor(
                    request.dispatchEvidence.dispatch.profile,
                    request.dispatchEvidence.observation,
                    completedAt,
                )
                require(request.expectedCapabilityDigest == null ||
                    request.expectedCapabilityDigest == value.capabilityDigest
                ) { "Available interoperability capability digest drifted." }
                require(request.expectedCatalogDigest == null ||
                    request.expectedCatalogDigest == value.mcpCatalog?.catalogDigest
                ) { "Available interoperability catalog digest drifted." }
            }
            AgentInteroperabilityCapabilityStatus.DRIFTED -> {
                val value = requireNotNull(snapshot) { "Drifted interoperability capabilities require an observation." }
                require(value.providerId == request.providerId) {
                    "Drifted interoperability capability snapshot came from another provider."
                }
                value.requireCurrentFor(
                    request.dispatchEvidence.dispatch.profile,
                    request.dispatchEvidence.observation,
                    completedAt,
                )
                require(failure?.category == AgentFailureCategory.PROTOCOL) {
                    "Drifted interoperability capabilities require a safe protocol failure."
                }
                require(
                    request.expectedCapabilityDigest != null &&
                        request.expectedCapabilityDigest != value.capabilityDigest ||
                        request.expectedCatalogDigest != null &&
                        request.expectedCatalogDigest != value.mcpCatalog?.catalogDigest,
                ) { "Drifted interoperability capabilities must differ from an expected digest." }
            }
            AgentInteroperabilityCapabilityStatus.UNSUPPORTED,
            AgentInteroperabilityCapabilityStatus.UNAVAILABLE -> require(snapshot == null && failure != null) {
                "Unavailable interoperability capabilities require a safe failure and no snapshot."
            }
        }
        resultDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.capability-result.v1",
        )
            .text(requestId.value)
            .text(requestDigest)
            .text(status.name)
            .optionalText(snapshot?.capabilityDigest)
            .optionalText(failure?.category?.value)
            .optionalText(failure?.code)
            .text(this.evidenceDigest)
            .longValue(completedAt)
            .finish()
    }

    override fun toString(): String =
        "AgentInteroperabilityCapabilityResult(status=$status, values=<redacted>)"

    companion object {
        @JvmStatic
        fun available(
            request: AgentInteroperabilityCapabilityRequest,
            snapshot: AgentInteroperabilityCapabilitySnapshot,
            evidenceDigest: String,
            completedAt: Long,
        ): AgentInteroperabilityCapabilityResult = AgentInteroperabilityCapabilityResult(
            request,
            AgentInteroperabilityCapabilityStatus.AVAILABLE,
            snapshot,
            null,
            evidenceDigest,
            completedAt,
        )

        @JvmStatic
        fun failure(
            request: AgentInteroperabilityCapabilityRequest,
            status: AgentInteroperabilityCapabilityStatus,
            snapshot: AgentInteroperabilityCapabilitySnapshot?,
            failure: AgentRunFailure,
            evidenceDigest: String,
            completedAt: Long,
        ): AgentInteroperabilityCapabilityResult {
            require(status != AgentInteroperabilityCapabilityStatus.AVAILABLE) {
                "Use available() for available interoperability capabilities."
            }
            return AgentInteroperabilityCapabilityResult(
                request,
                status,
                snapshot,
                failure,
                evidenceDigest,
                completedAt,
            )
        }
    }
}
