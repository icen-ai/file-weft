package ai.icen.fw.persistence.jdbc

import kotlin.test.Test
import kotlin.test.assertNotNull

class JdbcWorkflowQueryRepositoryConstructionTest {
    @Test
    fun `construction does not require a transaction or freeze a dialect`() {
        assertNotNull(JdbcWorkflowQueryRepository())
    }
}
