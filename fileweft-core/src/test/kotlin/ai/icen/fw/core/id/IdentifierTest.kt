package ai.icen.fw.core.id

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class IdentifierTest {
    @Test
    fun `preserves opaque values`() {
        val identifier = Identifier(" tenant-A ")

        assertEquals(" tenant-A ", identifier.value)
        assertEquals(Identifier(" tenant-A "), identifier)
    }

    @Test
    fun `rejects blank value`() {
        assertThrows<IllegalArgumentException> {
            Identifier("  ")
        }
    }
}
