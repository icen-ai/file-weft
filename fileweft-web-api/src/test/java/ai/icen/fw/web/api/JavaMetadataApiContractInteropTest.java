package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.document.DocumentMetadataCommand;
import ai.icen.fw.web.api.v1.metadata.MetadataFieldDto;
import ai.icen.fw.web.api.v1.metadata.MetadataSchemaDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaMetadataApiContractInteropTest {
    @Test
    void exposesImmutableJavaFriendlyMetadataContracts() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("amount", "12.50");
        DocumentMetadataCommand command = new DocumentMetadataCommand("invoice", source);
        source.put("amount", "private-change");

        MetadataFieldDto field = new MetadataFieldDto(
            "amount",
            "NUMBER",
            true,
            Collections.emptyList(),
            null,
            null
        );
        MetadataSchemaDto schema = new MetadataSchemaDto("invoice", "2", Collections.singletonList(field));

        assertEquals("12.50", command.getValues().get("amount"));
        assertEquals("invoice", command.getSchemaId());
        assertEquals("invoice", schema.getId());
        assertEquals("amount", schema.getFields().get(0).getName());
        assertThrows(UnsupportedOperationException.class, () -> command.getValues().put("amount", "mutated"));
        assertThrows(UnsupportedOperationException.class, () -> schema.getFields().clear());
    }

    @Test
    void documentMetadataAcceptsEmptyStringValuesButRetainsTransportBounds() {
        DocumentMetadataCommand emptyString = new DocumentMetadataCommand(
            "invoice",
            Collections.singletonMap("optionalNote", "")
        );

        assertEquals("", emptyString.getValues().get("optionalNote"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new DocumentMetadataCommand("invoice", Collections.singletonMap("note", "line\nbreak"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new DocumentMetadataCommand(
                "invoice",
                Collections.singletonMap("note", repeated("x", 16_385))
            )
        );

        Map<String, String> maximumSchema = metadataFields(128);
        assertEquals(128, new DocumentMetadataCommand("invoice", maximumSchema).getValues().size());
        assertThrows(
            IllegalArgumentException.class,
            () -> new DocumentMetadataCommand("invoice", metadataFields(129))
        );
    }

    private static Map<String, String> metadataFields(int count) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            fields.put("field" + index, "value");
        }
        return fields;
    }

    private static String repeated(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
