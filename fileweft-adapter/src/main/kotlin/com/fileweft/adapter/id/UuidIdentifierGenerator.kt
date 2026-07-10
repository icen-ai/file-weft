package com.fileweft.adapter.id

import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import java.util.UUID

/** Default UUID identifier source for deployments without an external ID service. */
class UuidIdentifierGenerator : IdentifierGenerator {
    override fun nextId(): Identifier = Identifier(UUID.randomUUID().toString().replace("-", ""))
}
