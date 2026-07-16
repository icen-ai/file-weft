package ai.icen.fw.capacity.persistence.jdbc

import ai.icen.fw.testkit.capacity.CapacityPersistenceContractTest
import ai.icen.fw.testkit.capacity.CapacityProviderContractHarness
import ai.icen.fw.testkit.capacity.CapacityProviderContractTest
import ai.icen.fw.testkit.capacity.CapacityRuntimeContractHarness
import ai.icen.fw.testkit.capacity.CapacityRuntimeContractTest

/** Runs the reusable provider contract against the production JDBC provider and real H2 tables. */
class JdbcCapacityProviderTestKitContractTest : CapacityProviderContractTest() {
    override fun newHarness(): CapacityProviderContractHarness = CapacityJdbcTestKitFixture.newHarness()
}

/** Runs the reusable runtime contract with the production JDBC provider behind the runtime. */
class JdbcCapacityRuntimeTestKitContractTest : CapacityRuntimeContractTest() {
    override fun newHarness(): CapacityRuntimeContractHarness =
        CapacityRuntimeContractHarness(CapacityJdbcTestKitFixture.newHarness())
}

/**
 * Uses only real V039 rows for intent, outcome, outbox, CAS, fencing, restart, and reconciliation
 * evidence. The fixture's fault controller operates at JDBC commit/connection boundaries.
 */
class JdbcCapacityPersistenceTestKitContractTest : CapacityPersistenceContractTest() {
    override fun newHarness(): CapacityProviderContractHarness = CapacityJdbcTestKitFixture.newHarness()
}
