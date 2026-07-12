package ai.icen.fw.spi.identity

import ai.icen.fw.core.id.Identifier

interface UserRealmProvider {
    fun currentUser(): UserIdentity?

    fun findUser(userId: Identifier): UserIdentity?
}
