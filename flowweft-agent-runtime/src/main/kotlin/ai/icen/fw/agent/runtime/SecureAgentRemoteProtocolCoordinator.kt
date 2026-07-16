package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCancellationException
import ai.icen.fw.agent.api.AgentRemoteAuthorizationDecision
import ai.icen.fw.agent.api.AgentRemoteAuthorizationOutcome
import ai.icen.fw.agent.api.AgentRemoteAuthorizationPhase
import ai.icen.fw.agent.api.AgentRemoteAuthorizationRequest
import ai.icen.fw.agent.api.AgentRemoteCredentialBroker
import ai.icen.fw.agent.api.AgentRemoteCredentialLease
import ai.icen.fw.agent.api.AgentRemoteCredentialLeaseRequest
import ai.icen.fw.agent.api.AgentRemoteNetworkResolution
import ai.icen.fw.agent.api.AgentRemoteNetworkResolutionRequest
import ai.icen.fw.agent.api.AgentRemoteNetworkResolver
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolBaselines
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchFailure
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchResult
import ai.icen.fw.agent.api.AgentRemoteProtocolInvocationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteProtocolProvider
import ai.icen.fw.agent.api.AgentRemoteProtocolReconciliationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolResultStatus
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/**
 * Provider-neutral MCP/A2A security coordinator.
 *
 * Remote adapters receive one already-resolved HTTPS hop and cannot follow redirects. The runtime
 * performs fresh authorization and obtains an audience-bound opaque credential lease immediately
 * before every dispatch. Once a hop is checkpointed as DISPATCHING, any provider exception becomes
 * OUTCOME_UNKNOWN and can only be closed by the reconciliation SPI.
 */
class SecureAgentRemoteProtocolCoordinator @JvmOverloads constructor(
    private val journal: AgentRemoteProtocolDispatchJournal,
    private val peerProfiles: AgentRemotePeerProfileRegistry,
    private val authorizationProviders: AgentRemoteAuthorizationProviderRegistry,
    private val networkResolver: AgentRemoteNetworkResolver,
    private val credentialBroker: AgentRemoteCredentialBroker,
    private val protocolProviders: AgentRemoteProtocolProviderRegistry,
    private val reconcilers: AgentRemoteProtocolReconcilerRegistry,
    private val clock: AgentRuntimeClock,
    private val ids: AgentRuntimeIdGenerator,
    private val configuration: AgentRemoteProtocolRuntimeConfiguration = AgentRemoteProtocolRuntimeConfiguration(),
) {
    fun start(request: AgentRemoteProtocolInvocationRequest): CompletionStage<AgentRemoteProtocolInvocationState> {
        val now = clock.currentTimeMillis()
        return try {
            request.requireCurrent(now)
            val profile = requireProfile(request)
            requireParentOperation(request, profile)
            val preparation = prepareHop(
                request,
                profile,
                request.operation.context,
                profile.resourceUri,
                null,
                0,
            )
            val output = CompletableFuture<AgentRemoteProtocolInvocationState>()
            preparation.whenComplete { prepared, failure ->
                if (failure != null || prepared == null) {
                    output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.preflight-failed"))
                } else {
                    reserveAndDispatch(request, profile, prepared, output)
                }
            }
            output
        } catch (_: AgentCancellationException) {
            failedStage(AgentRemoteProtocolRuntimeException("protocol.cancelled-before-admission"))
        } catch (failure: AgentRemoteProtocolRuntimeException) {
            failedStage(failure)
        } catch (_: RuntimeException) {
            failedStage(AgentRemoteProtocolRuntimeException("protocol.admission-failed"))
        }
    }

    /**
     * Queries a previously unknown outcome. This never invokes [AgentRemoteProtocolProvider.start]
     * and therefore cannot turn reconciliation into a duplicate side effect.
     */
    fun reconcile(
        context: AgentRunContext,
        invocationId: Identifier,
    ): CompletionStage<AgentRemoteProtocolInvocationState> {
        val output = CompletableFuture<AgentRemoteProtocolInvocationState>()
        try {
            val now = clock.currentTimeMillis()
            val key = AgentRemoteProtocolInvocationKey(context.tenantId, invocationId)
            val state = journal.load(key)
                ?: throw AgentRemoteProtocolRuntimeException("protocol.reconciliation-hidden-or-missing")
            if (state.key() != key || context.tenantId != state.tenantId ||
                context.principalId != state.principalId || context.principalType != state.principalType
            ) {
                throw AgentRemoteProtocolRuntimeException("protocol.reconciliation-hidden-or-missing")
            }
            require(state.status == AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN) {
                "Agent remote reconciliation requires an outcome-unknown state."
            }
            require(context.initiatedAt <= now) { "Agent remote reconciliation context time is invalid." }
            require(now < state.invocation.reconciliationDeadlineAt) {
                "Agent remote reconciliation deadline elapsed."
            }
            val profile = requireProfile(state.invocation)
            require(profile.profileDigest == state.profile.profileDigest) {
                "Agent remote peer profile drifted before reconciliation."
            }
            val originalDispatch = requireNotNull(state.lastDispatch) {
                "Agent remote outcome-unknown state lacks its original dispatch."
            }
            require(profile.resourceUri == originalDispatch.networkRequest.targetUri) {
                "Agent remote reconciliation target differs from the approved protected resource."
            }
            val networkRequest = AgentRemoteNetworkResolutionRequest(
                ids.nextId("agent-remote-reconciliation-resolution"),
                profile.peerId,
                profile.profileDigest,
                originalDispatch.networkRequest.targetUri,
                null,
                0,
                now,
                state.invocation.reconciliationDeadlineAt,
            )
            val resolutionStage = networkResolver.resolve(networkRequest)
            resolutionStage.whenComplete { resolution, resolutionFailure ->
                if (resolutionFailure != null || resolution == null) {
                    output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-resolution-failed"))
                } else {
                    authorizeAndReconcile(context, state, profile, networkRequest, resolution, output)
                }
            }
        } catch (failure: AgentRemoteProtocolRuntimeException) {
            output.completeExceptionally(failure)
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-rejected"))
        }
        return output
    }

    /**
     * Restores a crash-interrupted journal state without blindly replaying an already checkpointed
     * external call. DISPATCHING and RECONCILING become OUTCOME_UNKNOWN; only a never-dispatched
     * RESERVED operation may resume through fresh DNS and authorization.
     */
    fun recover(
        context: AgentRunContext,
        invocationId: Identifier,
    ): CompletionStage<AgentRemoteProtocolInvocationState> {
        val output = CompletableFuture<AgentRemoteProtocolInvocationState>()
        try {
            val now = clock.currentTimeMillis()
            val key = AgentRemoteProtocolInvocationKey(context.tenantId, invocationId)
            val state = journal.load(key)
                ?: throw AgentRemoteProtocolRuntimeException("protocol.recovery-hidden-or-missing")
            if (state.key() != key || context.tenantId != state.tenantId ||
                context.principalId != state.principalId || context.principalType != state.principalType
            ) {
                throw AgentRemoteProtocolRuntimeException("protocol.recovery-hidden-or-missing")
            }
            require(context.initiatedAt <= now) { "Agent remote recovery context time is invalid." }
            when (state.status) {
                AgentRemoteProtocolExecutionStatus.RESERVED -> {
                    if (now >= state.invocation.deadlineAt) {
                        output.complete(commit(
                            state,
                            state.failedBeforeDispatch("protocol.recovery-deadline-elapsed", stateTime(state)),
                        ))
                    } else {
                        val profile = requireProfile(state.invocation)
                        require(profile.profileDigest == state.profile.profileDigest) {
                            "Agent remote profile drifted before reserved-operation recovery."
                        }
                        requireParentOperation(state.invocation, profile)
                        val preparation = prepareHop(
                            state.invocation,
                            profile,
                            context,
                            profile.resourceUri,
                            null,
                            0,
                        )
                        preparation.whenComplete { prepared, failure ->
                            if (failure != null || prepared == null) {
                                completeFailedBeforeDispatch(state, "protocol.recovery-preflight-failed", output)
                            } else {
                                dispatchHop(state, prepared, output)
                            }
                        }
                    }
                }
                AgentRemoteProtocolExecutionStatus.DISPATCHING -> {
                    val dispatch = requireNotNull(state.lastDispatch)
                    output.complete(commit(
                        state,
                        state.outcomeUnknown(
                            unknownEvidence(dispatch, "protocol.recovered-dispatch-outcome-unknown"),
                            "protocol.recovered-dispatch-outcome-unknown",
                            reconciliationStateTime(state),
                        ),
                    ))
                }
                AgentRemoteProtocolExecutionStatus.RECONCILING -> output.complete(commit(
                    state,
                    state.reconciliationUnavailable(
                        "protocol.recovered-reconciliation-outcome-unknown",
                        reconciliationStateTime(state),
                    ),
                ))
                AgentRemoteProtocolExecutionStatus.REDIRECT_PENDING -> output.complete(commit(
                    state,
                    state.failedBeforeDispatch("protocol.redirect-recovery-unsupported", stateTime(state)),
                ))
                AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN,
                AgentRemoteProtocolExecutionStatus.SUCCEEDED,
                AgentRemoteProtocolExecutionStatus.FAILED,
                AgentRemoteProtocolExecutionStatus.CANCELLATION_CONFIRMED,
                AgentRemoteProtocolExecutionStatus.CANCELLATION_REJECTED,
                AgentRemoteProtocolExecutionStatus.CANCELLED_BEFORE_DISPATCH -> output.complete(state)
            }
        } catch (failure: AgentRemoteProtocolRuntimeException) {
            output.completeExceptionally(failure)
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.recovery-rejected"))
        }
        return output
    }

    private fun reserveAndDispatch(
        request: AgentRemoteProtocolInvocationRequest,
        profile: AgentRemotePeerProfile,
        preparation: HopPreparation,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            val now = stateTime(request.requestedAt, request.deadlineAt)
            request.requireCurrent(now)
            val initial = AgentRemoteProtocolInvocationState.initial(
                ids.nextId("agent-remote-invocation"),
                request,
                profile,
                now,
            )
            val reserved = journal.reserve(initial)
            when (reserved.status) {
                AgentRemoteProtocolReserveStatus.CREATED -> {
                    require(reserved.state.stateDigest == initial.stateDigest &&
                        reserved.state.status == AgentRemoteProtocolExecutionStatus.RESERVED
                    ) { "Agent remote journal created another invocation state." }
                    dispatchHop(reserved.state, preparation, output)
                }
                AgentRemoteProtocolReserveStatus.REPLAY -> {
                    require(reserved.state.idempotencyScope == initial.idempotencyScope &&
                        reserved.state.invocation.bindingDigest == request.bindingDigest &&
                        reserved.state.profile.profileDigest == profile.profileDigest
                    ) { "Agent remote idempotency key was reused with changed arguments or profile." }
                    output.complete(reserved.state)
                }
                AgentRemoteProtocolReserveStatus.CONFLICT -> output.completeExceptionally(
                    AgentRemoteProtocolRuntimeException("protocol.idempotency-conflict"),
                )
            }
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reserve-failed"))
        }
    }

    private fun prepareHop(
        invocation: AgentRemoteProtocolInvocationRequest,
        profile: AgentRemotePeerProfile,
        callerContext: AgentRunContext,
        targetUri: URI,
        previousUri: URI?,
        hopIndex: Int,
    ): CompletionStage<HopPreparation> {
        val now = clock.currentTimeMillis()
        invocation.requireCurrent(now)
        require(hopIndex <= profile.maximumRedirects) { "Agent remote redirect limit was exceeded." }
        require(sameOrigin(profile.resourceUri, targetUri)) {
            "Agent remote redirect changed the protected resource origin."
        }
        val networkRequest = AgentRemoteNetworkResolutionRequest(
            ids.nextId("agent-remote-network-resolution"),
            profile.peerId,
            profile.profileDigest,
            targetUri,
            previousUri,
            hopIndex,
            now,
            invocation.deadlineAt,
        )
        val resolutionStage = try {
            networkResolver.resolve(networkRequest)
        } catch (_: RuntimeException) {
            return failedStage(AgentRemoteProtocolRuntimeException("protocol.resolution-failed"))
        }
        return resolutionStage.thenCompose { resolution ->
            val authorizedAt = clock.currentTimeMillis()
            invocation.requireCurrent(authorizedAt)
            require(resolution.providerId == networkResolver.providerId()) {
                "Agent remote resolution came from another provider."
            }
            resolution.requirePublicAndCurrent(networkRequest, authorizedAt)
            val authorizationProvider = authorizationProviders.find(invocation.authorizationProviderId)
                ?: throw AgentRemoteProtocolRuntimeException("protocol.authorization-provider-missing")
            require(authorizationProvider.providerId() == invocation.authorizationProviderId) {
                "Agent remote authorization registry returned another provider."
            }
            val preflight = AgentRemoteAuthorizationRequest(
                ids.nextId("agent-remote-authorization-preflight"),
                invocation.authorizationProviderId,
                AgentRemoteAuthorizationPhase.PREFLIGHT,
                null,
                invocation,
                callerContext,
                profile,
                targetUri,
                resolution.bindingDigest,
                profile.credential.bindingDigest,
                hopIndex,
                authorizedAt,
                boundedExpiry(authorizedAt, configuration.authorizationTtlMillis, invocation.deadlineAt),
            )
            authorizationProvider.authorize(preflight).thenApply { decision ->
                val decidedAt = clock.currentTimeMillis()
                require(decision.providerId == authorizationProvider.providerId() &&
                    decision.outcome == AgentRemoteAuthorizationOutcome.ALLOW
                ) { "Agent remote preflight authorization was denied or came from another provider." }
                decision.requireAllowedFor(preflight, decidedAt)
                HopPreparation(networkRequest, resolution, preflight, decision)
            }
        }
    }

    private fun dispatchHop(
        state: AgentRemoteProtocolInvocationState,
        preparation: HopPreparation,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            val invocation = state.invocation
            val now = clock.currentTimeMillis()
            invocation.requireCurrent(now)
            requireCurrentProfile(state)
            val provider = protocolProviders.find(state.profile.peerId, state.profile.protocol)
            if (provider == null || provider.providerId() != state.profile.protocolProviderId ||
                provider.peerId() != state.profile.peerId || provider.protocol() != state.profile.protocol ||
                provider.bindingId() != state.profile.bindingId
            ) {
                completeFailedBeforeDispatch(state, "protocol.provider-missing", output)
                return
            }
            val authorizationProvider = authorizationProviders.find(invocation.authorizationProviderId)
            if (authorizationProvider == null || authorizationProvider.providerId() != invocation.authorizationProviderId) {
                completeFailedBeforeDispatch(state, "protocol.authorization-provider-missing", output)
                return
            }
            val finalRequest = AgentRemoteAuthorizationRequest(
                ids.nextId("agent-remote-authorization-final"),
                invocation.authorizationProviderId,
                AgentRemoteAuthorizationPhase.FINAL_DISPATCH,
                preparation.preflightRequest.requestId,
                invocation,
                preparation.preflightRequest.callerContext,
                state.profile,
                preparation.networkRequest.targetUri,
                preparation.resolution.bindingDigest,
                state.profile.credential.bindingDigest,
                state.hopIndex,
                now,
                boundedExpiry(now, configuration.authorizationTtlMillis, invocation.deadlineAt),
            )
            finalRequest.requireChildOf(preparation.preflightRequest)
            authorizationProvider.authorize(finalRequest).whenComplete { finalDecision, authorizationFailure ->
                if (authorizationFailure != null || finalDecision == null) {
                    completeFailedBeforeDispatch(state, "protocol.final-authorization-failed", output)
                } else {
                    leaseAndDispatch(
                        state,
                        provider,
                        preparation,
                        finalRequest,
                        finalDecision,
                        output,
                    )
                }
            }
        } catch (_: AgentCancellationException) {
            completeCancelledBeforeDispatch(state, "protocol.cancelled-before-dispatch", output)
        } catch (_: RuntimeException) {
            completeFailedBeforeDispatch(state, "protocol.dispatch-preparation-failed", output)
        }
    }

    private fun leaseAndDispatch(
        state: AgentRemoteProtocolInvocationState,
        provider: AgentRemoteProtocolProvider,
        preparation: HopPreparation,
        finalRequest: AgentRemoteAuthorizationRequest,
        finalDecision: AgentRemoteAuthorizationDecision,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            val now = clock.currentTimeMillis()
            state.invocation.requireCurrent(now)
            requireCurrentProfile(state)
            require(finalDecision.providerId == finalRequest.providerId &&
                finalDecision.outcome == AgentRemoteAuthorizationOutcome.ALLOW
            ) { "Agent remote final authorization was denied or came from another provider." }
            finalDecision.requireAllowedFor(finalRequest, now)
            require(finalDecision.authorizationRevision == preparation.preflightDecision.authorizationRevision) {
                "Agent remote authorization revision drifted between preflight and dispatch."
            }
            state.invocation.executionAuthorizationRevision?.let { expectedRevision ->
                require(finalDecision.authorizationRevision == expectedRevision) {
                    "Agent remote authorization revision changed after approval or one-time execution consumption."
                }
            }
            val credentialRequest = AgentRemoteCredentialLeaseRequest(
                ids.nextId("agent-remote-credential-lease"),
                state.profile,
                state.invocation.bindingDigest,
                finalRequest,
                finalDecision,
                now,
                boundedExpiry(
                    now,
                    configuration.credentialLeaseTtlMillis,
                    minOf(state.invocation.deadlineAt, finalDecision.expiresAt),
                ),
            )
            credentialBroker.lease(credentialRequest).whenComplete { lease, credentialFailure ->
                if (credentialFailure != null || lease == null) {
                    completeFailedBeforeDispatch(state, "protocol.credential-lease-failed", output)
                } else {
                    checkpointAndInvoke(
                        state,
                        provider,
                        preparation,
                        finalRequest,
                        finalDecision,
                        credentialRequest,
                        lease,
                        output,
                    )
                }
            }
        } catch (_: AgentCancellationException) {
            completeCancelledBeforeDispatch(state, "protocol.cancelled-before-dispatch", output)
        } catch (_: RuntimeException) {
            completeFailedBeforeDispatch(state, "protocol.final-authorization-invalid", output)
        }
    }

    private fun checkpointAndInvoke(
        state: AgentRemoteProtocolInvocationState,
        provider: AgentRemoteProtocolProvider,
        preparation: HopPreparation,
        finalRequest: AgentRemoteAuthorizationRequest,
        finalDecision: AgentRemoteAuthorizationDecision,
        credentialRequest: AgentRemoteCredentialLeaseRequest,
        lease: AgentRemoteCredentialLease,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        var dispatching: AgentRemoteProtocolInvocationState? = null
        var dispatch: AgentRemoteProtocolDispatchRequest? = null
        try {
            val now = clock.currentTimeMillis()
            state.invocation.requireCurrent(now)
            requireCurrentProfile(state)
            require(lease.brokerId == credentialBroker.brokerId()) {
                "Agent remote credential lease came from another broker."
            }
            lease.requireCurrentFor(credentialRequest, now)
            val dispatchRequest = AgentRemoteProtocolDispatchRequest(
                ids.nextId("agent-remote-dispatch"),
                state.invocation,
                state.profile,
                finalRequest,
                finalDecision,
                preparation.networkRequest,
                preparation.resolution,
                credentialRequest,
                lease,
                now,
            )
            dispatch = dispatchRequest
            val checkpointed = commit(
                state,
                state.dispatching(dispatchRequest, finalDecision.authorizationRevision, stateTime(state)),
            )
            dispatching = checkpointed
            val call = provider.start(dispatchRequest)
            call.completion().whenComplete { result, providerFailure ->
                if (providerFailure != null || result == null) {
                    val rejection = providerFailure?.let { preOperationRejection(it, dispatchRequest) }
                    if (rejection == null) {
                        completeOutcomeUnknown(
                            checkpointed,
                            "protocol.dispatch-outcome-unknown",
                            dispatchRequest,
                            output,
                        )
                    } else {
                        completeOperationRejected(checkpointed, rejection, output)
                    }
                } else {
                    processDispatchResult(checkpointed, result, output)
                }
            }
        } catch (_: AgentCancellationException) {
            if (dispatching == null) {
                completeCancelledBeforeDispatch(state, "protocol.cancelled-before-dispatch", output)
            } else {
                completeOutcomeUnknown(
                    dispatching,
                    "protocol.dispatch-outcome-unknown",
                    requireNotNull(dispatch),
                    output,
                )
            }
        } catch (failure: RuntimeException) {
            if (dispatching == null) {
                completeFailedBeforeDispatch(state, "protocol.dispatch-checkpoint-failed", output)
            } else {
                val checkpointedDispatch = requireNotNull(dispatch)
                val rejection = preOperationRejection(failure, checkpointedDispatch)
                if (rejection == null) {
                    completeOutcomeUnknown(
                        dispatching,
                        "protocol.dispatch-outcome-unknown",
                        checkpointedDispatch,
                        output,
                    )
                } else {
                    completeOperationRejected(dispatching, rejection, output)
                }
            }
        }
    }

    private fun processDispatchResult(
        state: AgentRemoteProtocolInvocationState,
        result: AgentRemoteProtocolDispatchResult,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            require(result.dispatchRequestId == state.lastDispatch?.requestId &&
                result.dispatchBindingDigest == state.lastDispatch?.bindingDigest
            ) { "Agent remote provider returned another dispatch result." }
            when (result.status) {
                AgentRemoteProtocolResultStatus.REDIRECT -> {
                    val redirected = commit(state, state.redirected(result, stateTime(state)))
                    val target = requireNotNull(result.redirectUri)
                    if (redirected.hopIndex > redirected.profile.maximumRedirects ||
                        !sameOrigin(redirected.profile.resourceUri, target)
                    ) {
                        completeFailedBeforeDispatch(redirected, "protocol.redirect-rejected", output)
                        return
                    }
                    val preparation = try {
                        prepareHop(
                            redirected.invocation,
                            redirected.profile,
                            redirected.invocation.operation.context,
                            target,
                            requireNotNull(state.lastDispatch).networkRequest.targetUri,
                            redirected.hopIndex,
                        )
                    } catch (_: RuntimeException) {
                        completeFailedBeforeDispatch(redirected, "protocol.redirect-rejected", output)
                        return
                    }
                    preparation.whenComplete { prepared, failure ->
                        if (failure != null || prepared == null) {
                            completeFailedBeforeDispatch(redirected, "protocol.redirect-rejected", output)
                        } else {
                            dispatchHop(redirected, prepared, output)
                        }
                    }
                }
                AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN -> {
                    val unknown = try {
                        state.outcomeUnknown(result, stateTime(state))
                    } catch (_: RuntimeException) {
                        state.outcomeUnknown(
                            unknownEvidence(requireNotNull(state.lastDispatch), "protocol.result-invalid"),
                            "protocol.result-invalid",
                            stateTime(state),
                        )
                    }
                    output.complete(commit(state, unknown))
                }
                else -> {
                    val completed = try {
                        state.completed(result, stateTime(state))
                    } catch (_: RuntimeException) {
                        val unknown = state.outcomeUnknown(
                            unknownEvidence(requireNotNull(state.lastDispatch), "protocol.result-invalid"),
                            "protocol.result-invalid",
                            stateTime(state),
                        )
                        output.complete(commit(state, unknown))
                        return
                    }
                    output.complete(commit(state, completed))
                }
            }
        } catch (_: RuntimeException) {
            completeOutcomeUnknown(
                state,
                "protocol.result-invalid",
                requireNotNull(state.lastDispatch),
                output,
            )
        }
    }

    private fun authorizeAndReconcile(
        callerContext: AgentRunContext,
        state: AgentRemoteProtocolInvocationState,
        profile: AgentRemotePeerProfile,
        networkRequest: AgentRemoteNetworkResolutionRequest,
        resolution: AgentRemoteNetworkResolution,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            val now = clock.currentTimeMillis()
            require(resolution.providerId == networkResolver.providerId()) {
                "Agent remote reconciliation resolution came from another provider."
            }
            resolution.requirePublicAndCurrent(networkRequest, now)
            val authorizationProvider = authorizationProviders.find(state.invocation.authorizationProviderId)
                ?: throw AgentRemoteProtocolRuntimeException("protocol.authorization-provider-missing")
            require(authorizationProvider.providerId() == state.invocation.authorizationProviderId) {
                "Agent remote reconciliation authorization provider changed."
            }
            val originalDispatch = requireNotNull(state.lastDispatch)
            val request = AgentRemoteAuthorizationRequest(
                ids.nextId("agent-remote-reconciliation-authorization"),
                state.invocation.authorizationProviderId,
                AgentRemoteAuthorizationPhase.RECONCILIATION,
                originalDispatch.authorizationRequest.requestId,
                state.invocation,
                callerContext,
                profile,
                networkRequest.targetUri,
                resolution.bindingDigest,
                profile.credential.bindingDigest,
                0,
                now,
                boundedExpiry(now, configuration.authorizationTtlMillis, state.invocation.reconciliationDeadlineAt),
            )
            authorizationProvider.authorize(request).whenComplete { decision, authorizationFailure ->
                if (authorizationFailure != null || decision == null) {
                    output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-authorization-failed"))
                } else {
                    leaseAndReconcile(state, profile, networkRequest, resolution, request, decision, output)
                }
            }
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-preparation-failed"))
        }
    }

    private fun leaseAndReconcile(
        state: AgentRemoteProtocolInvocationState,
        profile: AgentRemotePeerProfile,
        networkRequest: AgentRemoteNetworkResolutionRequest,
        resolution: AgentRemoteNetworkResolution,
        authorizationRequest: AgentRemoteAuthorizationRequest,
        decision: AgentRemoteAuthorizationDecision,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            val now = clock.currentTimeMillis()
            decision.requireAllowedFor(authorizationRequest, now)
            requireCurrentProfile(state)
            val reconciler = reconcilers.find(profile.peerId, profile.protocol)
            if (reconciler == null || reconciler.providerId() != profile.reconciliationProviderId ||
                reconciler.peerId() != profile.peerId || reconciler.protocol() != profile.protocol ||
                reconciler.bindingId() != profile.bindingId
            ) {
                output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciler-missing"))
                return
            }
            val credentialRequest = AgentRemoteCredentialLeaseRequest(
                ids.nextId("agent-remote-reconciliation-credential"),
                profile,
                state.invocation.bindingDigest,
                authorizationRequest,
                decision,
                now,
                boundedExpiry(
                    now,
                    configuration.credentialLeaseTtlMillis,
                    minOf(state.invocation.reconciliationDeadlineAt, decision.expiresAt),
                ),
            )
            credentialBroker.lease(credentialRequest).whenComplete { lease, leaseFailure ->
                if (leaseFailure != null || lease == null) {
                    output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-credential-failed"))
                } else {
                    checkpointAndReconcile(
                        state,
                        reconciler,
                        authorizationRequest,
                        decision,
                        networkRequest,
                        resolution,
                        credentialRequest,
                        lease,
                        output,
                    )
                }
            }
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-authorization-invalid"))
        }
    }

    private fun checkpointAndReconcile(
        state: AgentRemoteProtocolInvocationState,
        reconciler: ai.icen.fw.agent.api.AgentRemoteProtocolOutcomeReconciler,
        authorizationRequest: AgentRemoteAuthorizationRequest,
        decision: AgentRemoteAuthorizationDecision,
        networkRequest: AgentRemoteNetworkResolutionRequest,
        resolution: AgentRemoteNetworkResolution,
        credentialRequest: AgentRemoteCredentialLeaseRequest,
        lease: AgentRemoteCredentialLease,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        var reconciling: AgentRemoteProtocolInvocationState? = null
        try {
            val now = clock.currentTimeMillis()
            requireCurrentProfile(state)
            require(lease.brokerId == credentialBroker.brokerId()) {
                "Agent remote reconciliation credential came from another broker."
            }
            lease.requireCurrentFor(credentialRequest, now)
            val originalDispatch = requireNotNull(state.lastDispatch)
            val request = AgentRemoteProtocolReconciliationRequest(
                ids.nextId("agent-remote-reconciliation"),
                originalDispatch,
                state.lastDispatchResult,
                requireNotNull(state.unknownEvidenceDigest),
                authorizationRequest,
                decision,
                networkRequest,
                resolution,
                credentialRequest,
                lease,
                now,
            )
            val checkpointed = commit(
                state,
                state.reconciling(request, decision.authorizationRevision, reconciliationStateTime(state)),
            )
            reconciling = checkpointed
            val call = reconciler.reconcile(request)
            call.completion().whenComplete { result, reconciliationFailure ->
                if (reconciliationFailure != null || result == null) {
                    completeReconciliationUnavailable(checkpointed, output)
                } else {
                    try {
                        output.complete(commit(
                            checkpointed,
                            checkpointed.reconciled(result, reconciliationStateTime(checkpointed)),
                        ))
                    } catch (_: RuntimeException) {
                        completeReconciliationUnavailable(checkpointed, output)
                    }
                }
            }
        } catch (_: RuntimeException) {
            if (reconciling == null) {
                output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.reconciliation-checkpoint-failed"))
            } else {
                completeReconciliationUnavailable(reconciling, output)
            }
        }
    }

    private fun completeFailedBeforeDispatch(
        state: AgentRemoteProtocolInvocationState,
        code: String,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            output.complete(commit(state, state.failedBeforeDispatch(code, stateTime(state))))
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.journal-conflict"))
        }
    }

    private fun completeCancelledBeforeDispatch(
        state: AgentRemoteProtocolInvocationState,
        code: String,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            output.complete(commit(state, state.cancelledBeforeDispatch(code, stateTime(state))))
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.journal-conflict"))
        }
    }

    private fun completeOutcomeUnknown(
        state: AgentRemoteProtocolInvocationState,
        code: String,
        dispatch: AgentRemoteProtocolDispatchRequest,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            val unknown = state.outcomeUnknown(unknownEvidence(dispatch, code), code, stateTime(state))
            output.complete(commit(state, unknown))
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.journal-conflict"))
        }
    }

    private fun completeOperationRejected(
        state: AgentRemoteProtocolInvocationState,
        failure: AgentRemoteProtocolDispatchFailure,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            output.complete(commit(state, state.operationRejected(failure, stateTime(state))))
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.journal-conflict"))
        }
    }

    private fun completeReconciliationUnavailable(
        state: AgentRemoteProtocolInvocationState,
        output: CompletableFuture<AgentRemoteProtocolInvocationState>,
    ) {
        try {
            output.complete(commit(
                state,
                state.reconciliationUnavailable(
                    "protocol.reconciliation-unavailable",
                    reconciliationStateTime(state),
                ),
            ))
        } catch (_: RuntimeException) {
            output.completeExceptionally(AgentRemoteProtocolRuntimeException("protocol.journal-conflict"))
        }
    }

    private fun commit(
        current: AgentRemoteProtocolInvocationState,
        next: AgentRemoteProtocolInvocationState,
    ): AgentRemoteProtocolInvocationState {
        val result = journal.compareAndSet(
            AgentRemoteProtocolStateCommit(current.key(), current.stateVersion, current.stateDigest, next),
        )
        require(result.status == AgentRemoteProtocolCommitStatus.APPLIED && result.state != null &&
            result.state.stateDigest == next.stateDigest
        ) { "Agent remote journal compare-and-set failed." }
        return result.state
    }

    private fun requireProfile(request: AgentRemoteProtocolInvocationRequest): AgentRemotePeerProfile {
        val profile = peerProfiles.find(
            request.operation.context.tenantId,
            request.operation.peerId,
            request.operation.protocol,
        )
            ?: throw AgentRemoteProtocolRuntimeException("protocol.peer-profile-missing")
        if (profile.peerId != request.operation.peerId || profile.protocol != request.operation.protocol ||
            profile.profileDigest != request.approvedProfileDigest
        ) {
            throw AgentRemoteProtocolRuntimeException("protocol.peer-profile-drift")
        }
        when (profile.protocol) {
            AgentRemoteProtocolKind.MCP -> {
                if (profile.protocolVersion != AgentRemoteProtocolBaselines.MCP_2025_11_25) {
                    throw AgentRemoteProtocolRuntimeException("protocol.mcp-version-unsupported")
                }
                if (profile.bindingId != ai.icen.fw.agent.api.AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP) {
                    throw AgentRemoteProtocolRuntimeException("protocol.mcp-binding-unsupported")
                }
            }
            AgentRemoteProtocolKind.A2A -> {
                if (profile.protocolVersion != AgentRemoteProtocolBaselines.A2A_1_0) {
                    throw AgentRemoteProtocolRuntimeException("protocol.a2a-version-unsupported")
                }
                if (profile.bindingId != ai.icen.fw.agent.api.AgentRemoteProtocolBindingId.A2A_JSON_RPC_HTTP &&
                    profile.bindingId != ai.icen.fw.agent.api.AgentRemoteProtocolBindingId.A2A_GRPC &&
                    profile.bindingId != ai.icen.fw.agent.api.AgentRemoteProtocolBindingId.A2A_REST_HTTP
                ) {
                    throw AgentRemoteProtocolRuntimeException("protocol.a2a-binding-unsupported")
                }
            }
        }
        if (request.requiredCapability !in profile.capabilities) {
            throw AgentRemoteProtocolRuntimeException("protocol.capability-unsupported")
        }
        try {
            profile.requireOperation(request.operation)
        } catch (_: IllegalArgumentException) {
            throw AgentRemoteProtocolRuntimeException("protocol.descriptor-unapproved")
        }
        profile.credential.requireOwnedBy(profile.peerId, profile.resourceUri)
        return profile
    }

    private fun requireCurrentProfile(state: AgentRemoteProtocolInvocationState) {
        val current = requireProfile(state.invocation)
        require(current.profileDigest == state.profile.profileDigest) {
            "Agent remote peer profile changed after admission."
        }
    }

    private fun requireParentOperation(
        request: AgentRemoteProtocolInvocationRequest,
        profile: AgentRemotePeerProfile,
    ) {
        if (request.operation.operation != ai.icen.fw.agent.api.AgentRemoteOperationKind.A2A_CANCEL_TASK) return
        val parentId = requireNotNull(request.operation.parentInvocationId)
        val parentKey = AgentRemoteProtocolInvocationKey(request.operation.context.tenantId, parentId)
        val parent = journal.load(parentKey)
            ?: throw AgentRemoteProtocolRuntimeException("protocol.parent-task-hidden-or-missing")
        if (parent.key() != parentKey || parent.tenantId != request.operation.context.tenantId ||
            parent.principalId != request.operation.context.principalId ||
            parent.principalType != request.operation.context.principalType
        ) {
            throw AgentRemoteProtocolRuntimeException("protocol.parent-task-hidden-or-missing")
        }
        require(
            parent.status == AgentRemoteProtocolExecutionStatus.SUCCEEDED &&
            parent.invocation.operation.runId == request.operation.runId &&
            parent.invocation.authorizationProviderId == request.authorizationProviderId &&
            parent.profile.peerId == profile.peerId && parent.profile.protocol == AgentRemoteProtocolKind.A2A &&
            parent.profile.profileDigest == profile.profileDigest &&
            parent.invocation.operation.operation == ai.icen.fw.agent.api.AgentRemoteOperationKind.A2A_SEND_MESSAGE &&
            parent.invocation.operation.bindingDigest == request.operation.parentOperationDigest
        ) { "Agent remote cancellation parent belongs to another subject, run, peer or operation." }
        val parentReconciliation = parent.reconciliationResult
        val parentDispatchResult = parent.lastDispatchResult
        val boundRemoteTaskId = when {
            parentReconciliation?.outcome ==
                ai.icen.fw.agent.api.AgentRemoteProtocolReconciliationOutcome.SUCCEEDED ->
                parentReconciliation.remoteTaskId
            parentDispatchResult?.status == AgentRemoteProtocolResultStatus.SUCCEEDED ->
                parentDispatchResult.remoteTaskId
            else -> null
        }
        require(boundRemoteTaskId != null && boundRemoteTaskId == request.operation.remoteTaskId) {
            "Agent remote cancellation task was not issued by its bound parent operation."
        }
    }

    private fun stateTime(state: AgentRemoteProtocolInvocationState): Long =
        maxOf(state.updatedAt, minOf(clock.currentTimeMillis(), state.invocation.deadlineAt))

    private fun reconciliationStateTime(state: AgentRemoteProtocolInvocationState): Long =
        maxOf(state.updatedAt, minOf(clock.currentTimeMillis(), state.invocation.reconciliationDeadlineAt))

    private fun stateTime(requestedAt: Long, deadlineAt: Long): Long =
        maxOf(requestedAt, minOf(clock.currentTimeMillis(), deadlineAt))

    private fun boundedExpiry(now: Long, ttlMillis: Long, deadlineAt: Long): Long {
        val ttlDeadline = if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE else now + ttlMillis
        val expiry = minOf(ttlDeadline, deadlineAt)
        require(expiry > now) { "Agent remote authorization or credential window elapsed." }
        return expiry
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) && effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

    private fun unknownEvidence(dispatch: AgentRemoteProtocolDispatchRequest, code: String): String =
        AgentRuntimeDigest("flowweft.agent.remote.outcome-unknown.v1")
            .add(dispatch.bindingDigest)
            .add(requireRuntimeCode(code, "Agent remote unknown outcome code is invalid."))
            .finish()

    private fun preOperationRejection(
        failure: Throwable,
        dispatch: AgentRemoteProtocolDispatchRequest,
    ): AgentRemoteProtocolDispatchFailure? {
        var current: Throwable = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        val classified = current as? AgentRemoteProtocolDispatchFailure ?: return null
        return if (!classified.operationFrameMayHaveReachedPeer && classified.isBoundTo(dispatch)) classified else null
    }

    private class HopPreparation(
        val networkRequest: AgentRemoteNetworkResolutionRequest,
        val resolution: AgentRemoteNetworkResolution,
        val preflightRequest: AgentRemoteAuthorizationRequest,
        val preflightDecision: AgentRemoteAuthorizationDecision,
    )
}

private fun <T> failedStage(failure: Throwable): CompletionStage<T> =
    CompletableFuture<T>().also { future -> future.completeExceptionally(failure) }
