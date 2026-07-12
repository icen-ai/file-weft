package ai.icen.fw.adapter.id

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import java.util.UUID

/** Default UUID identifier source for deployments without an external ID service. */
class UuidIdentifierGenerator : IdentifierGenerator {
    override fun nextId(): Identifier = Identifier(UUID.randomUUID().toString().replace("-", ""))
}
