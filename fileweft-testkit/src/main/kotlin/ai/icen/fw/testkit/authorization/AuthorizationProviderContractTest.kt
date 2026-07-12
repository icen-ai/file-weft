package ai.icen.fw.testkit.authorization

import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class AuthorizationProviderContractTest {
    protected abstract val authorizationProvider: AuthorizationProvider

    protected abstract fun allowedRequest(): AuthorizationRequest

    protected abstract fun deniedRequest(): AuthorizationRequest

    @Test
    fun `allows the configured permitted request`() {
        assertTrue(authorizationProvider.authorize(allowedRequest()).allowed)
    }

    @Test
    fun `denies the configured forbidden request`() {
        assertFalse(authorizationProvider.authorize(deniedRequest()).allowed)
    }
}
