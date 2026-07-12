package ai.icen.fw.adapter.identity

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider

/**
 * Safe identity fallback for deployments that have not connected an identity
 * system yet. It deliberately never synthesizes a user identity.
 */
class DefaultUserRealmProvider : UserRealmProvider {
    override fun currentUser(): UserIdentity? = null

    override fun findUser(userId: Identifier): UserIdentity? = null
}
