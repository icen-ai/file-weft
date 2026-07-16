# FlowWeft Reliability TestKit

`flowweft-reliability-testkit` is the reusable, provider-neutral contract suite
for the public reliability API and durable runtime. It is deliberately a test
artifact: it has no Spring dependency, vendor SDK, network requirement, or
production persistence implementation.

The testkit supplies:

- deterministic clocks and identifiers;
- a strict trusted-host authorization fixture that binds the exact tenant,
  principal, purpose, action, resource revision, and short dispatch window;
- single-database and database/object-storage/search-index topology fixtures;
- a mutation-counting provider probe and a deterministic provider double whose
  reconciliation path can only refer to the exact original attempt;
- strict in-memory run and SLO repositories for testing the testkit itself;
- canonical durable rehydration assertions, including mandatory rejection of
  an independently persisted digest mismatch;
- reusable JUnit 5 suites for crash recovery, tenant isolation, CAS/fencing,
  repository semantics, and missing-SLI fail-closed alerts.

## Consumer usage

An adapter or host should subclass the relevant suite in its own test source
set. The runtime suite composes the host/provider/repository boundary; the
repository suites can be run independently.

```kotlin
class AcmeRecoveryContractTest : ReliabilityRuntimeRecoveryContractTest() {
    override fun newHarness(): ReliabilityRuntimeContractHarness =
        ReliabilityRuntimeContractHarness.of(
            ReliabilityTopologyFixtures.multiComponent("tenant-contract", 100_000L),
            acmeRunRepository(),
            acmeReliabilityProvider(),
            acmeProviderDescriptor(),
        )
}

class AcmeRunRepositoryContractTest : ReliabilityRunRepositoryContractTest() {
    override fun newRepository(): ReliabilityRunRepository = acmeRunRepository()
}
```

Each test must receive isolated repository state. The provider used by the
recovery suite must implement successful backup creation and exact read-only
reconciliation for the fixture topology. A real database or service remains a
separate integration lane; the included in-memory doubles are not evidence for
PostgreSQL, MySQL, KingbaseES, object storage, or search-index recovery.

The testkit uses only synthetic opaque values and SHA-256-shaped digests. Do
not put passwords, tokens, signed URLs, object paths, connection strings, or
provider exception messages into fixture identifiers, assertion messages, or
test output.

## Architecture change control

The reliability API/runtime extension points already cover this work, so this
module adds no production SPI and changes no existing semantics. It depends
inward on the public API/runtime and JUnit only. Consumers migrate by adding the
test-scoped artifact and subclassing one or more contract suites; production
configuration is unchanged.
