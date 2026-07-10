package com.fileweft.adapter.identity

import com.fileweft.core.id.Identifier
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider

/**
 * Safe identity fallback for deployments that have not connected an identity
 * system yet. It deliberately never synthesizes a user identity.
 */
class DefaultUserRealmProvider : UserRealmProvider {
    override fun currentUser(): UserIdentity? = null

    override fun findUser(userId: Identifier): UserIdentity? = null
}
