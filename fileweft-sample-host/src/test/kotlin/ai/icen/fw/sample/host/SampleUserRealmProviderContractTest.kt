package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.testkit.identity.UserRealmProviderContractTest

class SampleUserRealmProviderContractTest : UserRealmProviderContractTest() {

    override val userRealmProvider = SampleUserRealmProvider(
        users = mapOf(
            knownUserId() to UserIdentity(id = knownUserId(), displayName = "Known User"),
        ),
    )

    override fun knownUserId(): Identifier = Identifier("sample-known-user")

    override fun unknownUserId(): Identifier = Identifier("sample-unknown-user")
}
