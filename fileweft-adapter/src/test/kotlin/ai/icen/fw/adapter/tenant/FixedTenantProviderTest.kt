package ai.icen.fw.adapter.tenant

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FixedTenantProviderTest {
    @Test
    fun `returns one immutable tenant context without normalizing the external identifier`() {
        val provider = FixedTenantProvider("tenant:external/001")

        val first = provider.currentTenant()

        assertEquals("tenant:external/001", first.tenantId.value)
        assertSame(first, provider.currentTenant())
    }

    @Test
    fun `rejects a blank fixed tenant at construction time`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FixedTenantProvider("   ")
        }

        assertEquals("Identifier value must not be blank.", failure.message)
    }
}
