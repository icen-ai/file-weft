package ai.icen.fw.web.runtime.v1.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HealthApiFacadeTest {
    @Test
    fun `reports only process liveness`() {
        assertEquals("UP", HealthApiFacade().inspect().status)
    }
}
