package ai.icen.fw.workflow.cycle.guard.persistence.jdbc

import org.h2.jdbcx.JdbcDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

internal object CycleGuardJdbcH2Fixture {
    fun prepared(name: String): DataSource = dataSource(name).also(::initialize)

    fun dataSource(name: String): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:cycle-guard-$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        user = "sa"
        password = ""
    }

    fun initialize(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(H2_INSTANCE_TABLE) }
            val path = WorkflowCycleGuardJdbcSchemaDialect.POSTGRESQL.resourcePath
            val sql = checkNotNull(javaClass.getResourceAsStream(path)).use {
                String(it.readBytes(), StandardCharsets.UTF_8)
            }
            sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { ddl ->
                connection.createStatement().use { it.execute(ddl) }
            }
        }
    }

    fun insertInstance(
        dataSource: DataSource,
        tenantId: String,
        instanceId: String,
        instanceVersion: Long,
        subjectRevision: String,
        subjectDigest: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_INSTANCE_SQL).use { statement ->
                statement.setString(1, instanceId)
                statement.setString(2, tenantId)
                statement.setString(3, DEFINITION_ID)
                statement.setString(4, DEFINITION_KEY)
                statement.setString(5, DEFINITION_VERSION)
                statement.setString(6, DEFINITION_DIGEST)
                statement.setString(7, SUBJECT_TYPE)
                statement.setString(8, SUBJECT_ID)
                statement.setString(9, subjectRevision)
                statement.setString(10, subjectDigest)
                statement.setLong(11, instanceVersion)
                statement.setLong(12, 1L)
                statement.setLong(13, 1L)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    fun updateInstanceSubject(
        dataSource: DataSource,
        tenantId: String,
        instanceId: String,
        instanceVersion: Long,
        subjectRevision: String,
        subjectDigest: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(UPDATE_INSTANCE_SQL).use { statement ->
                statement.setLong(1, instanceVersion)
                statement.setString(2, subjectRevision)
                statement.setString(3, subjectDigest)
                statement.setLong(4, instanceVersion)
                statement.setString(5, tenantId)
                statement.setString(6, instanceId)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    fun count(dataSource: DataSource, table: String, tenantId: String): Int {
        require(table in TABLES)
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE tenant_id = ?").use { statement ->
                statement.setString(1, tenantId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }
    }

    fun failFirstCommitAfterSuccess(delegate: DataSource): DataSource = CommitFailureDataSource(delegate)

    private class CommitFailureDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        private val firstCommit = AtomicBoolean(true)

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(username: String?, password: String?): Connection =
            wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(
            connection.javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            if (method.name == "commit" && firstCommit.compareAndSet(true, false)) {
                connection.commit()
                throw SQLException("Simulated acknowledgement loss after commit.", "08006")
            }
            try {
                method.invoke(connection, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
    }

    const val DEFINITION_ID = "definition-1"
    const val DEFINITION_KEY = "expense"
    const val DEFINITION_VERSION = "1"
    const val SUBJECT_TYPE = "document"
    const val SUBJECT_ID = "document-1"
    const val DEFINITION_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    private val TABLES = setOf(
        "fw_wf_cycle_guard_total",
        "fw_wf_cycle_guard_cycle",
        "fw_wf_cycle_guard_receipt",
    )

    private const val H2_INSTANCE_TABLE = """
        CREATE TABLE fw_wf_instance (
            id varchar(512) NOT NULL,
            tenant_id varchar(512) NOT NULL,
            definition_id varchar(512) NOT NULL,
            definition_key varchar(256) NOT NULL,
            definition_version varchar(128) NOT NULL,
            definition_digest varchar(64) NOT NULL,
            subject_type varchar(64) NOT NULL,
            subject_id varchar(512) NOT NULL,
            subject_revision varchar(256) NOT NULL,
            subject_digest varchar(64) NOT NULL,
            instance_version bigint NOT NULL,
            created_time bigint NOT NULL,
            updated_time bigint NOT NULL,
            PRIMARY KEY (tenant_id, id)
        )
    """

    private const val INSERT_INSTANCE_SQL = """
        INSERT INTO fw_wf_instance (
            id, tenant_id, definition_id, definition_key, definition_version, definition_digest,
            subject_type, subject_id, subject_revision, subject_digest, instance_version,
            created_time, updated_time
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    private const val UPDATE_INSTANCE_SQL = """
        UPDATE fw_wf_instance SET instance_version = ?, subject_revision = ?, subject_digest = ?, updated_time = ?
        WHERE tenant_id = ? AND id = ?
    """
}
