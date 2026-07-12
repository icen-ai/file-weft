package ai.icen.fw.persistence.migration;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlywayMigrationJavaInteropTest {
    @Test
    void exposesStableJavaConstructorsConstantsAndModes() {
        DataSource dataSource = new PGSimpleDataSource();

        new FlywayMigrationRunner(dataSource);
        new FlywayMigrationRunner(dataSource, "public");
        new FlywayMigrationRunner(dataSource, "public", false);

        assertEquals("classpath:ai/icen/fw/db/migration", FlywayMigrationRunner.MIGRATION_LOCATION);
        assertEquals("fileweft_schema_history", FlywayMigrationRunner.HISTORY_TABLE);
        assertEquals(FileWeftMigrationMode.DISABLED, FileWeftMigrationMode.valueOf("DISABLED"));
        assertEquals(FileWeftMigrationMode.VALIDATE, FileWeftMigrationMode.valueOf("VALIDATE"));
        assertEquals(FileWeftMigrationMode.MIGRATE, FileWeftMigrationMode.valueOf("MIGRATE"));
    }
}
