package ai.icen.fw.web.runtime.v1.health

import ai.icen.fw.web.api.v1.health.HealthDto

/** Dependency-free process liveness boundary. */
class HealthApiFacade {
    fun inspect(): HealthDto = HealthDto(UP)

    private companion object {
        const val UP: String = "UP"
    }
}
