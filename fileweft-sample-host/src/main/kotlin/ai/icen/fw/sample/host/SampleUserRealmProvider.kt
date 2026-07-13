package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider

/**
 * Sample host identity provider backed by an in-memory user registry.
 */
class SampleUserRealmProvider(
    private val users: Map<Identifier, UserIdentity> = emptyMap(),
    private val fallbackCurrentUser: UserIdentity? = null,
) : UserRealmProvider {

    override fun currentUser(): UserIdentity? = fallbackCurrentUser

    override fun findUser(userId: Identifier): UserIdentity? = users[userId]
}
