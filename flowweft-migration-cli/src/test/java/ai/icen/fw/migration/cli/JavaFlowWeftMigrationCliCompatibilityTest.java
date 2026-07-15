package ai.icen.fw.migration.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class JavaFlowWeftMigrationCliCompatibilityTest {
    @Test
    void stableProcessContractIsConsumableFromJava8() {
        assertEquals(0, FlowWeftMigrationExitCode.SUCCESS);
        assertEquals(2, FlowWeftMigrationExitCode.INVALID_CONFIGURATION);
        assertEquals(10, FlowWeftMigrationExitCode.MIGRATION_FAILED);
        assertEquals("legacy", FlowWeftMigrationLine.LEGACY.getId());
        assertEquals("workflow", FlowWeftMigrationLine.WORKFLOW.getId());
        assertEquals(FlowWeftMigrationMode.MIGRATE, FlowWeftMigrationMode.valueOf("MIGRATE"));
        assertEquals(FlowWeftMigrationMode.VALIDATE, FlowWeftMigrationMode.valueOf("VALIDATE"));
    }
}
