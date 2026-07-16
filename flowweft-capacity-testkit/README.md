# FlowWeft Capacity TestKit

`flowweft-capacity-testkit` is the reusable, provider-neutral contract suite for
the FlowWeft 1.0 capacity API, runtime, and durable provider boundary. It is a
test artifact only: it has no Spring dependency, vendor SDK, network client, or
production persistence implementation.

The testkit supplies:

- deterministic clocks, identifiers, trusted contexts, and a complete
  system -> tenant -> provider -> resource policy hierarchy;
- canonical units, warning/critical watermarks, and a deliberately narrow
  capacity-only degradation intersection;
- a thread-safe atomic provider with stable digest idempotency, CAS, fenced
  reservation leases, admission/throttle/reject/degrade decisions, and exact
  read-only outcome reconciliation;
- a mutation-counting provider/reconciliation probe;
- a test-only, value-free persistence inspection port for PREPARED,
  NOT_APPLIED, and APPLIED intents, canonical outcome digests, and outbox
  evidence counts;
- reusable JUnit 5 provider, runtime, and persistence contract suites covering
  tenant isolation, concurrent CAS, lease fencing, two-phase outcomes,
  fail-closed unknown outcomes, and restart reconciliation.

## Consumer usage

Adapters subclass only the suites they implement. Every test must receive
isolated provider and repository state.

```kotlin
class AcmeCapacityProviderContractTest : CapacityProviderContractTest() {
    override fun newHarness(): CapacityProviderContractHarness = acmeHarness()
}

class AcmeCapacityPersistenceContractTest : CapacityPersistenceContractTest() {
    override fun newHarness(): CapacityProviderContractHarness = acmeHarness()
}
```

`CapacityProviderContractHarness` accepts the production provider, policy
source, read-only reconciliation port, and optional test-only persistence
inspection/fault controls. The persistence suite requires those optional
controls and a restart factory; the provider and runtime suites do not.

The reference fixture follows the JDBC capacity decision baseline: a request
below critical pressure is admitted, a critical request without an approved
degradation is throttled, the same request with the exact policy-approved
capacity-only capability is degraded and reserved, and a request above the hard
limit is rejected.

The public capacity API intentionally has no general-purpose object
deserialization hook. Consequently restart coverage requires the restarted
provider to return a digest-identical canonical decision or receipt through the
exact read-only reconciliation port. JDBC codec/migration correctness and real
PostgreSQL, MySQL 8, and KingbaseES durability remain separate integration
lanes; this in-memory fixture is not database evidence.
The bundled concrete behavior tests validate this TestKit itself only; a
production persistence claim requires a JDBC-backed harness subclass plus the
corresponding external database lane.

Never put raw idempotency keys, credentials, connection strings, tenant or
resource labels, provider exceptions, or payloads into inspection output or
assertion messages.

## Architecture change control

The existing capacity API/runtime ports already cover provider composition and
reconciliation, so this module adds no production SPI and changes no existing
semantics. It depends inward on the public capacity modules and JUnit only.
