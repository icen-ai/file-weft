# FlowWeft Governance TestKit

`flowweft-governance-testkit` is the reusable, provider-neutral contract suite
for retention, legal-hold resolution, and secure deletion. It has no Spring,
vendor SDK, network, or production persistence dependency.

The public testkit supplies:

- deterministic clocks, identifiers, trusted invocations, and strict fresh
  authorization evidence;
- controllable clear, active, and incomplete legal-hold resolution fixtures;
- an exact resource-bound retention policy and the canonical evaluator;
- a mutation-counting deletion provider whose read-only reconciliation can
  address only the exact original operation reference;
- strict in-memory deletion/outbox repositories for testing the suites;
- canonical assessment, receipt, and run rehydration assertions, including
  independently persisted digest mismatch rejection;
- reusable JUnit 5 suites for legal-hold fail-closed behavior, crash recovery,
  tenant isolation, compare-and-set, and outbox fencing.

## Consumer usage

Subclass each relevant suite in the consumer test source set. Every test must
receive isolated storage and provider state.

```kotlin
class AcmeDeletionRecoveryContractTest : GovernanceDeletionRecoveryContractTest() {
    override fun newHarness(): GovernanceRuntimeContractHarness =
        GovernanceRuntimeContractHarness.of(
            GovernanceRepositoryBundle.of(acmeDeletionRepository(), acmeOutboxRepository()),
            acmeObservableProviderProbe(),
        )
}

class AcmeGovernanceRepositoryContractTest : GovernanceRepositoryContractTest() {
    override fun newRepositories(): GovernanceRepositoryBundle =
        GovernanceRepositoryBundle.of(acmeDeletionRepository(), acmeOutboxRepository())
}
```

The provider probe used by the recovery suite must expose mutation counts and
prove that reconciliation reads the exact operation reference from the prior
`OUTCOME_UNKNOWN` receipt. Reconciliation must never perform a second delete.
The included in-memory repositories and provider are self-tests only; they are
not evidence for PostgreSQL, MySQL, KingbaseES, object storage, or an external
search/index implementation.

All fixture values are synthetic and opaque. Never put passwords, tokens,
signed URLs, object paths, connection strings, legal matter text, provider
exception messages, or personal data into fixture identifiers or test output.

## Architecture change control

The governance API and runtime already expose the required provider and
repository boundaries. This module adds no production SPI and changes no
existing semantics. Consumers add it only to test scope; production
configuration and migrations are unchanged.
