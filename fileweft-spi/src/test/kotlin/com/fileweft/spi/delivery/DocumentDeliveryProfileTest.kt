package com.fileweft.spi.delivery

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentDeliveryProfileTest {
    @Test
    fun `defensively copies delivery targets and preserves route invariants`() {
        val originalTargets = mutableListOf(requiredTarget("archive"))

        val profile = DocumentDeliveryProfile("regulated", "Regulated", originalTargets)
        originalTargets.clear()
        originalTargets += DocumentDeliveryTargetDefinition(
            "optional-only",
            "Optional only",
            "search",
            DeliveryRequirement.OPTIONAL,
        )

        assertEquals(listOf("archive"), profile.targets.map { it.id })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (profile.targets as MutableList<DocumentDeliveryTargetDefinition>).clear()
        }
    }

    @Test
    fun `rejects ambiguous target routes and routes without a required target`() {
        assertFailsWith<IllegalArgumentException> {
            DocumentDeliveryProfile(
                "duplicate",
                "Duplicate",
                listOf(requiredTarget("same"), requiredTarget("same")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentDeliveryProfile(
                "optional-only",
                "Optional only",
                listOf(
                    DocumentDeliveryTargetDefinition(
                        "search",
                        "Search",
                        "search",
                        DeliveryRequirement.OPTIONAL,
                    ),
                ),
            )
        }
    }

    private fun requiredTarget(id: String): DocumentDeliveryTargetDefinition = DocumentDeliveryTargetDefinition(
        id = id,
        displayName = id,
        connectorId = "$id-connector",
        requirement = DeliveryRequirement.REQUIRED,
    )
}
