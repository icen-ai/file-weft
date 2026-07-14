package ai.icen.fw.metadata.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaMetadataApiCompatibilityTest {
    @Test
    void contractsRemainCallableAndImmutableFromJava8() {
        List<String> choices = new ArrayList<String>(Arrays.asList("A", "B"));
        MetadataField field = new MetadataField(
            "category",
            MetadataFieldType.ENUM,
            true,
            choices,
            Integer.valueOf(8)
        );
        MetadataSchema schema = new MetadataSchema("document", "1", Collections.singletonList(field));
        MetadataSchemaContext context = new MetadataSchemaContext(
            "tenant-java",
            "document",
            "DOCUMENT",
            "UPLOAD"
        );
        MetadataValue value = new MetadataValue("category", "A");

        choices.add("C");

        assertEquals(Arrays.asList("A", "B"), field.getAllowedValues());
        assertEquals(field, schema.findField("category"));
        assertEquals("tenant-java", context.getTenantId());
        assertEquals(null, context.getSchemaVersion());
        assertEquals("A", value.getValue());
        assertThrows(UnsupportedOperationException.class, () -> field.getAllowedValues().add("D"));

        MetadataValidationResult failure = new MetadataValidationResult(
            Collections.singletonList(
                new MetadataValidationIssue(MetadataValidationIssueCode.UNKNOWN_FIELD, "other")
            )
        );
        assertFalse(failure.getValid());
        assertFalse(failure.isValid());
        assertTrue(MetadataValidationResult.success().isValid());
    }

    @Test
    void processorAndResolverArePlainJavaInterfaces() {
        MetadataSchema schema = new MetadataSchema("document", "1", Collections.<MetadataField>emptyList());
        MetadataSchemaResolver resolver = context -> schema;
        MetadataProcessor processor = new MetadataProcessor() {
            @Override
            public Map<String, String> process(
                MetadataSchemaContext context,
                Map<String, String> metadata
            ) {
                return metadata;
            }
        };

        MetadataSchemaContext context = new MetadataSchemaContext(
            "tenant-java",
            "document",
            "DOCUMENT",
            "READ",
            "1"
        );
        assertEquals(schema, resolver.resolve(context));
        assertEquals(Collections.emptyMap(), processor.process(context, Collections.<String, String>emptyMap()));
    }
}
