package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowCommentDocument;
import ai.icen.fw.workflow.api.WorkflowCommentToken;
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessDecision;
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessMode;
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessReport;
import ai.icen.fw.workflow.api.WorkflowFormFieldPath;
import ai.icen.fw.workflow.api.WorkflowFormVersionRef;
import ai.icen.fw.workflow.api.WorkflowJsonSchemaDialect;
import ai.icen.fw.workflow.api.WorkflowJsonSchemaRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaWorkflowFormAndCommentCompatibilityTest {
    @Test
    void contractsAreUsableFromPureJava() {
        WorkflowJsonSchemaRef schema = WorkflowJsonSchemaRef.of(
            "host-registry",
            "expense",
            "1",
            WorkflowJsonSchemaDialect.JSON_SCHEMA_2020_12,
            repeat('a', 64)
        );
        WorkflowFormVersionRef form = WorkflowFormVersionRef.of(
            "expense",
            "2",
            schema,
            null,
            null,
            repeat('b', 64)
        );
        WorkflowFormFieldPath path = WorkflowFormFieldPath.of("/amount");
        WorkflowFormFieldAccessReport report = WorkflowFormFieldAccessReport.of(
            Collections.singletonList(WorkflowFormFieldAccessDecision.of(
                path,
                WorkflowFormFieldAccessMode.ALLOW,
                WorkflowFormFieldAccessMode.ALLOW
            )),
            repeat('c', 64)
        );
        WorkflowPrincipalRef principal = WorkflowPrincipalRef.of("user", "u-1");
        WorkflowCommentDocument comment = WorkflowCommentDocument.of(Arrays.asList(
            WorkflowCommentToken.text("hello "),
            WorkflowCommentToken.mention(principal, "User One")
        ));

        assertEquals("2", form.getVersion());
        assertEquals(WorkflowFormFieldAccessMode.ALLOW, report.readMode(path));
        assertFalse(report.mayWrite(WorkflowFormFieldPath.of("/unknown")));
        assertEquals("u-1", comment.getMentionedPrincipals().get(0).getId());
    }

    private static String repeat(char character, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(character);
        return result.toString();
    }
}
