package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.authorization.AuthorizationDecision
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PermissionDoctorCheckerTest {
    @Test
    fun `reports missing current user`() {
        val result = PermissionDoctorChecker(
            FixedUserRealmProvider(null),
            FixedAuthorizationProvider(AuthorizationDecision(true)),
        ).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
    }

    @Test
    fun `reports denied authorization and successful authorization`() {
        val denied = PermissionDoctorChecker(
            FixedUserRealmProvider(),
            FixedAuthorizationProvider(AuthorizationDecision(false, "missing grant")),
        ).check(context())
        val granted = PermissionDoctorChecker(
            FixedUserRealmProvider(),
            FixedAuthorizationProvider(AuthorizationDecision(true)),
        ).check(context())

        assertEquals(DoctorStatus.ERROR, denied.status)
        assertEquals(DoctorStatus.HEALTHY, granted.status)
    }

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))
}
