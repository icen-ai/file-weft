package ai.icen.fw.testkit.identity

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.identity.UserRealmProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

abstract class UserRealmProviderContractTest {
    protected abstract val userRealmProvider: UserRealmProvider

    protected abstract fun knownUserId(): Identifier

    protected abstract fun unknownUserId(): Identifier

    @Test
    fun `finds a known user by id`() {
        val user = userRealmProvider.findUser(knownUserId())

        assertNotNull(user, "Known user must be found.")
        assertEquals(knownUserId(), user?.id, "Found user id must match the requested id.")
    }

    @Test
    fun `returns null for an unknown user`() {
        assertNull(userRealmProvider.findUser(unknownUserId()), "Unknown user must not resolve.")
    }
}
