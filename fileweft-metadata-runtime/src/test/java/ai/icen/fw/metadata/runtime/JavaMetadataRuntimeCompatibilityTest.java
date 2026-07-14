package ai.icen.fw.metadata.runtime;

import ai.icen.fw.metadata.api.MetadataField;
import ai.icen.fw.metadata.api.MetadataFieldType;
import ai.icen.fw.metadata.api.MetadataProcessor;
import ai.icen.fw.metadata.api.MetadataSchema;
import ai.icen.fw.metadata.api.MetadataSchemaContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaMetadataRuntimeCompatibilityTest {
    @Test
    void registryAndProcessorRemainCallableFromJava8() {
        MetadataSchema schema = new MetadataSchema(
            "document",
            "1",
            Arrays.asList(
                new MetadataField("amount", MetadataFieldType.NUMBER),
                new MetadataField("enabled", MetadataFieldType.BOOLEAN),
                new MetadataField("tags", MetadataFieldType.STRING_LIST)
            )
        );
        MetadataSchemaRegistry registry = new MetadataSchemaRegistry(Collections.singletonList(schema));
        MetadataProcessor processor = new DefaultMetadataProcessor(registry);
        MetadataSchemaContext context = new MetadataSchemaContext(
            "tenant-java",
            "document",
            "DOCUMENT",
            "UPLOAD",
            "1"
        );
        Map<String, String> input = new LinkedHashMap<String, String>();
        input.put("tags", "[ \"a\", \"b\" ]");
        input.put("enabled", "FALSE");
        input.put("amount", "1.2500");

        Map<String, String> output = processor.process(context, input);

        assertEquals("1.25", output.get("amount"));
        assertEquals("false", output.get("enabled"));
        assertEquals("[\"a\",\"b\"]", output.get("tags"));
        assertEquals(schema, registry.findCurrent("document"));
        assertEquals(schema, registry.findExact("document", "1"));
        assertThrows(UnsupportedOperationException.class, () -> output.put("other", "value"));
    }

    @Test
    void historicalSchemaContributionAndExactLookupRemainCallableFromJava8() {
        MetadataSchema current = new MetadataSchema(
            "document",
            "2",
            Collections.singletonList(new MetadataField("title", MetadataFieldType.STRING))
        );
        MetadataSchema historical = new MetadataSchema(
            "document",
            "1",
            Collections.singletonList(new MetadataField("legacyTitle", MetadataFieldType.STRING))
        );
        HistoricalMetadataSchema contribution = new HistoricalMetadataSchema(historical);
        MetadataSchemaRegistry registry = new MetadataSchemaRegistry(
            Collections.singletonList(current),
            Collections.singletonList(contribution.getSchema())
        );

        assertEquals(historical, contribution.getSchema());
        assertEquals(current, registry.findCurrent("document"));
        assertEquals(historical, registry.findExact("document", "1"));
        assertEquals(
            historical,
            registry.resolve(new MetadataSchemaContext("tenant-java", "document", "DOCUMENT", "DOCTOR", "1"))
        );
    }

    @Test
    void validationFailuresExposeOnlyTheStructuredResult() {
        MetadataSchema schema = new MetadataSchema(
            "document",
            "1",
            Collections.singletonList(new MetadataField("amount", MetadataFieldType.NUMBER))
        );
        DefaultMetadataProcessor processor = new DefaultMetadataProcessor(
            new MetadataSchemaRegistry(Collections.singletonList(schema))
        );
        MetadataSchemaContext context = new MetadataSchemaContext(
            "tenant-java",
            "document",
            "DOCUMENT",
            "UPLOAD"
        );

        MetadataValidationException failure = assertThrows(
            MetadataValidationException.class,
            () -> processor.process(context, Collections.singletonMap("amount", "secret-not-a-number"))
        );

        assertEquals(MetadataValidationException.MESSAGE, failure.getMessage());
        assertEquals(1, failure.getValidationResult().getIssues().size());
    }
}
