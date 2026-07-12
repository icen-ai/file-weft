package ai.icen.fw.core.context

import ai.icen.fw.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Tenant-scoped input supplied to a diagnostic checker. A document is optional
 * so the same checker contract can support both document and system diagnosis.
 */
class DoctorCheckContext @JvmOverloads constructor(
    val tenantId: Identifier,
    val documentId: Identifier? = null,
    attributes: Map<String, String> = emptyMap(),
) {
    val attributes: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(attributes))
}
