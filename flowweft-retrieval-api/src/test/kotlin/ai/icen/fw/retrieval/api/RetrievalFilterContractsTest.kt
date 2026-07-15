package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RetrievalFilterContractsTest {
    @Test
    fun `mandatory filter is an immutable conjunction of exact scalar clauses`() {
        val cohorts = mutableListOf("cohort-b", "Cohort-a")
        val filter = MandatoryFilter.builder("fw.acl", "1")
            .addIn("fw_acl_cohort", cohorts)
            .addEquals("fw_tenant_id", " tenant-A ")
            .build()
        cohorts.clear()

        assertEquals(listOf("fw_acl_cohort", "fw_tenant_id"), filter.clauses.map { clause -> clause.fieldId })
        assertEquals(listOf("Cohort-a", "cohort-b"), filter.clauses[0].values)
        assertEquals(" tenant-A ", filter.clauses[1].values.single())
        assertTrue(filter.digest.matches(Regex("[0-9a-f]{64}")))
        assertFailsWith<UnsupportedOperationException> {
            (filter.clauses as MutableList<RequiredFilterClause>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (filter.clauses[0].values as MutableList<String>).clear()
        }
    }

    @Test
    fun `digest is stable for equivalent AND and IN order but remains case sensitive`() {
        val first = MandatoryFilter.create(
            "fw.acl",
            "7",
            listOf(
                RequiredFilterClause.equalTo("fw_tenant_id", "tenant-A"),
                RequiredFilterClause.inValues("fw_acl_cohort", listOf("cohort-b", "cohort-a")),
            ),
        )
        val reordered = MandatoryFilter.create(
            "fw.acl",
            "7",
            listOf(
                RequiredFilterClause.inValues("fw_acl_cohort", listOf("cohort-a", "cohort-b")),
                RequiredFilterClause.equalTo("fw_tenant_id", "tenant-A"),
            ),
        )
        val changedCase = MandatoryFilter.create(
            "fw.acl",
            "7",
            listOf(
                RequiredFilterClause.inValues("fw_acl_cohort", listOf("cohort-a", "Cohort-b")),
                RequiredFilterClause.equalTo("fw_tenant_id", "tenant-A"),
            ),
        )

        assertEquals(first.digest, reordered.digest)
        assertNotEquals(first.digest, changedCase.digest)
    }

    @Test
    fun `rejects empty duplicate and ambiguous clause definitions`() {
        assertFailsWith<IllegalArgumentException> {
            RequiredFilterClause.inValues("fw_acl_cohort", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            RequiredFilterClause.inValues("fw_acl_cohort", listOf("same", "same"))
        }
        assertFailsWith<IllegalArgumentException> {
            RequiredFilterClause.inValues(
                "fw_acl_cohort",
                (0..RetrievalContractLimits.MAX_CLAIM_VALUES).map { index -> "cohort-$index" },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RequiredFilterClause.equalTo("fw_acl_cohort", "v".repeat(1_025))
        }
        assertFailsWith<IllegalArgumentException> {
            RequiredFilterClause.create(
                "fw_tenant_id",
                RequiredFilterOperator.EQUALS,
                listOf("tenant-a", "tenant-b"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MandatoryFilter.create("fw.acl", "1", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            MandatoryFilter.create(
                "fw.acl",
                "1",
                listOf(
                    RequiredFilterClause.equalTo("fw_tenant_id", "tenant-a"),
                    RequiredFilterClause.equalTo("fw_tenant_id", "tenant-b"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MandatoryFilter.builder("fw.acl", "1")
                .addEquals("fw_tenant_id", "tenant-a")
                .addEquals("fw_tenant_id", "tenant-b")
        }
    }

    @Test
    fun `capability accepts only a completely translatable schema and conjunction`() {
        val capability = MandatoryFilterCapability.builder("fw.acl", "1")
            .supportField("fw_tenant_id", setOf(RequiredFilterOperator.EQUALS))
            .supportField("fw_acl_cohort", setOf(RequiredFilterOperator.IN))
            .limits(2, 4, 5, 4_096)
            .build()
        val supported = MandatoryFilter.builder("fw.acl", "1")
            .addEquals("fw_tenant_id", "tenant-a")
            .addIn("fw_acl_cohort", listOf("cohort-a", "cohort-b"))
            .build()

        capability.requireSupports(supported)

        assertFailsWith<IllegalArgumentException> {
            capability.requireSupports(
                MandatoryFilter.builder("fw.acl", "1")
                    .addEquals("fw_tenant_id", "tenant-a")
                    .addEquals("unknown", "value")
                    .build(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            capability.requireSupports(
                MandatoryFilter.builder("fw.acl", "1")
                    .addEquals("fw_tenant_id", "tenant-a")
                    .addEquals("fw_acl_cohort", "cohort-a")
                    .build(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            capability.requireSupports(
                MandatoryFilter.builder("fw.acl", "2")
                    .addEquals("fw_tenant_id", "tenant-a")
                    .build(),
            )
        }
    }

    @Test
    fun `capability fails closed for every declared limit without truncating the filter`() {
        val filter = MandatoryFilter.builder("fw.acl", "1")
            .addEquals("field-a", "value-a")
            .addIn("field-b", listOf("value-b1", "value-b2"))
            .build()
        val supportedFields = mapOf(
            "field-a" to setOf(RequiredFilterOperator.EQUALS),
            "field-b" to setOf(RequiredFilterOperator.IN),
        )

        assertFailsWith<IllegalArgumentException> {
            capability(supportedFields, maxClauses = 1).requireSupports(filter)
        }
        assertFailsWith<IllegalArgumentException> {
            capability(supportedFields, maxValuesPerClause = 1).requireSupports(filter)
        }
        assertFailsWith<IllegalArgumentException> {
            capability(supportedFields, maxTotalValues = 2).requireSupports(filter)
        }
        assertFailsWith<IllegalArgumentException> {
            capability(supportedFields, maxPayloadBytes = 1).requireSupports(filter)
        }

        assertEquals(2, filter.clauses.size)
        assertEquals(listOf("value-b1", "value-b2"), filter.clauses[1].values)
    }

    @Test
    fun `capability snapshots mutable field operator declarations`() {
        val operators = linkedSetOf(RequiredFilterOperator.EQUALS)
        val fields = linkedMapOf<String, Collection<RequiredFilterOperator>>("fw_tenant_id" to operators)
        val capability = MandatoryFilterCapability.create(
            "fw.acl",
            "1",
            fields,
            1,
            1,
            1,
            4_096,
        )
        operators.clear()
        fields.clear()

        capability.requireSupports(
            MandatoryFilter.builder("fw.acl", "1")
                .addEquals("fw_tenant_id", "tenant-a")
                .build(),
        )
        assertEquals(setOf(RequiredFilterOperator.EQUALS), capability.supportedFields["fw_tenant_id"])
        assertFailsWith<UnsupportedOperationException> {
            (capability.supportedFields as MutableMap<String, Set<RequiredFilterOperator>>).clear()
        }
    }

    @Test
    fun `backend native scope binds every authority field and rejects another provider`() {
        val request = authorizationRequest()
        val scope = BackendNativeScope.create(
            request,
            "authority-a",
            "provider-instance-a",
            "d".repeat(64),
            "scope-a",
            "revision-7",
            110L,
            500L,
        )
        val same = BackendNativeScope.create(
            request,
            "authority-a",
            "provider-instance-a",
            "d".repeat(64),
            "scope-a",
            "revision-7",
            110L,
            500L,
        )
        val anotherAuthority = BackendNativeScope.create(
            request,
            "authority-b",
            "provider-instance-a",
            "d".repeat(64),
            "scope-a",
            "revision-7",
            110L,
            500L,
        )

        assertEquals(scope.digest, same.digest)
        assertNotEquals(scope.digest, anotherAuthority.digest)
        assertNotEquals(
            scope.digest,
            nativeScope(providerInstanceId = "provider-instance-b").digest,
        )
        assertNotEquals(
            scope.digest,
            nativeScope(securityDomainDigest = "e".repeat(64)).digest,
        )
        assertNotEquals(
            scope.digest,
            nativeScope(scopeReference = "scope-b").digest,
        )
        assertNotEquals(
            scope.digest,
            nativeScope(authorityRevision = "revision-8").digest,
        )
        assertTrue(scope.digest.matches(Regex("[0-9a-f]{64}")))
        scope.requireProviderInstance("provider-instance-a")
        assertFailsWith<IllegalArgumentException> {
            scope.requireProviderInstance("provider-instance-b")
        }
    }

    private fun capability(
        supportedFields: Map<String, Collection<RequiredFilterOperator>>,
        maxClauses: Int = 2,
        maxValuesPerClause: Int = 2,
        maxTotalValues: Int = 3,
        maxPayloadBytes: Int = 4_096,
    ): MandatoryFilterCapability = MandatoryFilterCapability.create(
        "fw.acl",
        "1",
        supportedFields,
        maxClauses,
        maxValuesPerClause,
        maxTotalValues,
        maxPayloadBytes,
    )

    private fun nativeScope(
        request: RetrievalAuthorizationRequest = authorizationRequest(),
        authorityId: String = "authority-a",
        providerInstanceId: String = "provider-instance-a",
        securityDomainDigest: String = "d".repeat(64),
        scopeReference: String = "scope-a",
        authorityRevision: String = "revision-7",
    ): BackendNativeScope = BackendNativeScope.create(
        request,
        authorityId,
        providerInstanceId,
        securityDomainDigest,
        scopeReference,
        authorityRevision,
        110L,
        500L,
    )

    private fun authorizationRequest(): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest.create(
        Identifier("authorization-filter"),
        Identifier("tenant-filter"),
        RetrievalAuthorizationSubject.create(
            RetrievalPrincipal.create(Identifier("user-filter"), "USER"),
            emptyMap(),
        ),
        "document:read",
        "filter-test",
        100L,
    )
}
