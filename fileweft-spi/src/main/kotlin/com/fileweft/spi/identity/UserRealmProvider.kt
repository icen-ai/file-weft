package com.fileweft.spi.identity

import com.fileweft.core.id.Identifier

interface UserRealmProvider {
    fun currentUser(): UserIdentity?

    fun findUser(userId: Identifier): UserIdentity?
}
