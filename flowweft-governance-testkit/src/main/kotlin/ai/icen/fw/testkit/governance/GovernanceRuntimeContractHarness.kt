package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceResourceRef
import ai.icen.fw.governance.api.GovernanceRetentionAssessment
import ai.icen.fw.governance.api.GovernanceRetentionEvaluator
import ai.icen.fw.governance.api.GovernanceVersionFence
import ai.icen.fw.governance.runtime.GovernanceAuthorizedCallFactory
import ai.icen.fw.governance.runtime.GovernanceDeletionEvidenceResolver
import ai.icen.fw.governance.runtime.GovernanceDeletionPlanCommand
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import ai.icen.fw.governance.runtime.GovernanceDeletionTarget
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetPort
import ai.icen.fw.governance.runtime.GovernanceMetric
import ai.icen.fw.governance.runtime.GovernanceMetricsPort
import ai.icen.fw.governance.runtime.GovernanceOutboxRecord
import ai.icen.fw.governance.runtime.GovernancePlanningResult
import ai.icen.fw.governance.runtime.GovernanceTrustedInvocation
import ai.icen.fw.governance.runtime.GovernanceWorkerCommand
import ai.icen.fw.governance.runtime.GovernanceWorkerResult
import ai.icen.fw.governance.runtime.GovernanceWorkerSignalPort
import ai.icen.fw.governance.runtime.ProviderNeutralGovernanceDeletionWorker
import ai.icen.fw.governance.runtime.ProviderNeutralGovernancePlanningRuntime
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/** Complete synthetic host composition used by the public governance contract suites. */
class GovernanceRuntimeContractHarness private constructor(
    val repositories: GovernanceRepositoryBundle,
    val providerProbe: GovernanceObservableProviderProbe,
    val tenantId: String,
    val principal: GovernancePrincipalRef,
    val resource: GovernanceResourceRef,
    val fence: GovernanceVersionFence,
    val clock: DeterministicGovernanceClock,
    val identifiers: DeterministicGovernanceIds,
    val authorization: StrictGovernanceAuthorizationFixture,
    val legalHolds: ControllableGovernanceLegalHoldResolver,
    val retentionPolicy: DeterministicGovernanceRetentionPolicy,
    val planning: ProviderNeutralGovernancePlanningRuntime,
    val worker: ProviderNeutralGovernanceDeletionWorker,
    private val targetResolutions: AtomicLong,
    metrics: MutableList<GovernanceMetric>,
    signals: MutableList<GovernanceOutboxRecord>,
) {
    val metrics: List<GovernanceMetric> = Collections.unmodifiableList(metrics)
    val signals: List<GovernanceOutboxRecord> = Collections.unmodifiableList(signals)

    @JvmOverloads
    fun invocation(
        purpose: GovernancePurpose,
        rawIdempotencyKey: String,
        tenantId: String = this.tenantId,
        principal: GovernancePrincipalRef = this.principal,
    ): GovernanceTrustedInvocation = GovernanceTrustedInvocation.of(
        tenantId,
        principal,
        purpose,
        resource,
        rawIdempotencyKey,
        clock.nowEpochMilli(),
        Math.addExact(clock.nowEpochMilli(), 1_000L),
    )

    @JvmOverloads
    fun plan(
        rawIdempotencyKey: String = "contract-plan",
        dryRun: Boolean = false,
    ): CompletionStage<GovernancePlanningResult> = planning.plan(
        GovernanceDeletionPlanCommand.of(
            invocation(GovernancePurpose.PLAN_SECURE_DELETION, rawIdempotencyKey),
            fence,
            dryRun,
        ),
    )

    fun execute(run: GovernanceDeletionRun, rawIdempotencyKey: String): CompletionStage<GovernanceWorkerResult> =
        worker.process(
            GovernanceWorkerCommand.of(
                invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, rawIdempotencyKey),
                run.planId,
            ),
        )

    fun reconcile(run: GovernanceDeletionRun, rawIdempotencyKey: String): CompletionStage<GovernanceWorkerResult> =
        worker.process(
            GovernanceWorkerCommand.of(
                invocation(GovernancePurpose.RECONCILE_SECURE_DELETION, rawIdempotencyKey),
                run.planId,
            ),
        )

    fun evaluateClearRetention(rawIdempotencyKey: String): CompletionStage<GovernanceRetentionAssessment> {
        legalHolds.setState(GovernanceLegalHoldFixtureState.CLEAR)
        return plan(rawIdempotencyKey, true).thenApply { result ->
            requireNotNull(result.plan).assessment
        }
    }

    fun targetResolutionCount(): Long = targetResolutions.get()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inMemory(
            tenantId: String = "tenant-contract",
            nowEpochMilli: Long = 100_000L,
        ): GovernanceRuntimeContractHarness = of(
            GovernanceRepositoryBundle.inMemory(),
            MutationCountingGovernanceProviderProbe.create(),
            tenantId,
            nowEpochMilli,
        )

        @JvmStatic
        @JvmOverloads
        fun of(
            repositories: GovernanceRepositoryBundle,
            providerProbe: GovernanceObservableProviderProbe,
            tenantId: String = "tenant-contract",
            nowEpochMilli: Long = 100_000L,
        ): GovernanceRuntimeContractHarness {
            require(nowEpochMilli >= 10_000L) { "Governance contract fixture time is too small." }
            val principal = GovernancePrincipalRef.of("user", "contract-operator")
            val resource = GovernanceResourceRef.of(
                "document",
                "contract-document",
                "1",
                GovernanceContractAssertions.digest('a'),
            )
            val fence = GovernanceVersionFence.of(resource, 7L)
            val clock = DeterministicGovernanceClock.startingAt(nowEpochMilli)
            val identifiers = DeterministicGovernanceIds.create()
            val authorization = StrictGovernanceAuthorizationFixture.forTenant(tenantId, principal)
            val calls = GovernanceAuthorizedCallFactory(authorization, identifiers)
            val holds = ControllableGovernanceLegalHoldResolver.forTenant(tenantId)
            val policies = DeterministicGovernanceRetentionPolicy.forTenant(tenantId)
            val evidence = GovernanceDeletionEvidenceResolver(
                calls,
                clock,
                holds,
                policies,
                GovernanceRetentionEvaluator { request -> GovernanceRetentionAssessment.evaluate(request) },
            )
            val targetResolutions = AtomicLong()
            val targets = GovernanceDeletionTargetPort {
                targetResolutions.incrementAndGet()
                CompletableFuture.completedFuture(
                    GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed { index, stage ->
                        GovernanceDeletionTarget.of(
                            stage,
                            "contract-target-${index + 1}",
                            "1",
                            ((index % 6) + 1).toString().repeat(64),
                        )
                    },
                )
            }
            val metrics = Collections.synchronizedList(mutableListOf<GovernanceMetric>())
            val signals = Collections.synchronizedList(mutableListOf<GovernanceOutboxRecord>())
            val metricPort = GovernanceMetricsPort { metric -> metrics.add(metric) }
            val signalPort = GovernanceWorkerSignalPort { record ->
                signals.add(record)
                CompletableFuture.completedFuture(null)
            }
            val planning = ProviderNeutralGovernancePlanningRuntime(
                evidence,
                calls,
                clock,
                identifiers,
                targets,
                repositories.deletion,
                signalPort,
                metricPort,
                24L * 60L * 60L * 1_000L,
            )
            val worker = ProviderNeutralGovernanceDeletionWorker(
                evidence,
                calls,
                clock,
                identifiers,
                repositories.deletion,
                providerProbe.registry(),
                metricPort,
                1_000L,
            )
            return GovernanceRuntimeContractHarness(
                repositories,
                providerProbe,
                tenantId,
                principal,
                resource,
                fence,
                clock,
                identifiers,
                authorization,
                holds,
                policies,
                planning,
                worker,
                targetResolutions,
                metrics,
                signals,
            )
        }
    }
}
