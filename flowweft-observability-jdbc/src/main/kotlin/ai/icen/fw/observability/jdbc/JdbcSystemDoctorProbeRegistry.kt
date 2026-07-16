package ai.icen.fw.observability.jdbc

import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorProbe
import ai.icen.fw.observability.SystemDoctorProbeRegistry
import java.util.Collections

/** Exact descriptor-bound registry; capability-only fallback is forbidden. */
class JdbcSystemDoctorProbeRegistry(probes: Collection<JdbcSystemDoctorProbe>) : SystemDoctorProbeRegistry {
    private val probes: Map<ProbeKey, JdbcSystemDoctorProbe>
    val descriptors: List<JdbcSystemDoctorProbeDescriptor>

    init {
        val snapshot = ArrayList(probes)
        require(snapshot.isNotEmpty() && snapshot.size <= SystemDoctorCapability.values().size) {
            "JDBC System Doctor probe inventory is invalid."
        }
        val byKey = LinkedHashMap<ProbeKey, JdbcSystemDoctorProbe>()
        snapshot.forEach { probe ->
            val descriptor = probe.descriptor()
            require(byKey.put(ProbeKey(descriptor.capability, descriptor.probeId), probe) == null) {
                "JDBC System Doctor probe inventory contains duplicate bindings."
            }
        }
        require(snapshot.map { probe -> probe.descriptor().capability }.distinct().size == snapshot.size) {
            "JDBC System Doctor permits at most one aggregate probe per capability."
        }
        this.probes = Collections.unmodifiableMap(byKey)
        descriptors = Collections.unmodifiableList(snapshot.map { probe -> probe.descriptor() })
    }

    override fun find(capability: SystemDoctorCapability, probeId: String): SystemDoctorProbe? =
        probes[ProbeKey(capability, probeId)]

    override fun toString(): String = "JdbcSystemDoctorProbeRegistry(probeCount=${probes.size})"
}

private data class ProbeKey(
    val capability: SystemDoctorCapability,
    val probeId: String,
)
