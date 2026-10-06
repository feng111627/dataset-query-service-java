package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaCompatibility;
import com.github.feng111627.datasetqueryservicejava.tests.support.BusinessCoverageLogger;
import com.github.feng111627.datasetqueryservicejava.tests.support.CoversScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("schema evolution: compatible re-registration accepted, incompatible changes rejected with INCOMPATIBLE_SCHEMA")
@ExtendWith(BusinessCoverageLogger.class)
class SchemaCompatibilityTest {

    private final SchemaCompatibility compatibility = new SchemaCompatibility();

    private final DatasetSchema existing = new DatasetSchema("events", List.of(
            new FieldDef("user", FieldType.STRING, true),
            new FieldDef("amount", FieldType.DOUBLE, true),
            new FieldDef("count", FieldType.INTEGER, false)
    ));

    @Test
    @DisplayName("identical schema re-registration is compatible")
    void identicalSchema() {
        assertDoesNotThrow(() -> compatibility.checkCompatible(existing, existing));
    }

    @Test
    @DisplayName("adding an optional field is compatible")
    void addingOptionalField() {
        DatasetSchema incoming = new DatasetSchema("events", List.of(
                new FieldDef("user", FieldType.STRING, true),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("count", FieldType.INTEGER, false),
                new FieldDef("tag", FieldType.STRING, false)
        ));
        assertDoesNotThrow(() -> compatibility.checkCompatible(existing, incoming));
    }

    @Test
    @DisplayName("relaxing a field from required to optional is compatible")
    void relaxingRequired() {
        DatasetSchema incoming = new DatasetSchema("events", List.of(
                new FieldDef("user", FieldType.STRING, false),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("count", FieldType.INTEGER, false)
        ));
        assertDoesNotThrow(() -> compatibility.checkCompatible(existing, incoming));
    }

    @Test
    @DisplayName("removing a field -> INCOMPATIBLE_SCHEMA")
    void removingField() {
        DatasetSchema incoming = new DatasetSchema("events", List.of(
                new FieldDef("user", FieldType.STRING, true),
                new FieldDef("amount", FieldType.DOUBLE, true)
        ));
        ClientException ex = assertThrows(ClientException.class,
                () -> compatibility.checkCompatible(existing, incoming));
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("count") && d.contains("removed")));
    }

    @Test
    @DisplayName("changing a field type -> INCOMPATIBLE_SCHEMA")
    void changingType() {
        DatasetSchema incoming = new DatasetSchema("events", List.of(
                new FieldDef("user", FieldType.STRING, true),
                new FieldDef("amount", FieldType.INTEGER, true),
                new FieldDef("count", FieldType.INTEGER, false)
        ));
        ClientException ex = assertThrows(ClientException.class,
                () -> compatibility.checkCompatible(existing, incoming));
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("amount")));
    }

    @Test
    @DisplayName("adding a required field -> INCOMPATIBLE_SCHEMA")
    void addingRequiredField() {
        DatasetSchema incoming = new DatasetSchema("events", List.of(
                new FieldDef("user", FieldType.STRING, true),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("count", FieldType.INTEGER, false),
                new FieldDef("region", FieldType.STRING, true)
        ));
        ClientException ex = assertThrows(ClientException.class,
                () -> compatibility.checkCompatible(existing, incoming));
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("region")));
    }

    @Test
    @DisplayName("making an optional field required -> INCOMPATIBLE_SCHEMA")
    void makingFieldRequired() {
        DatasetSchema incoming = new DatasetSchema("events", List.of(
                new FieldDef("user", FieldType.STRING, true),
                new FieldDef("amount", FieldType.DOUBLE, true),
                new FieldDef("count", FieldType.INTEGER, true)
        ));
        ClientException ex = assertThrows(ClientException.class,
                () -> compatibility.checkCompatible(existing, incoming));
        assertEquals(ErrorCode.INCOMPATIBLE_SCHEMA, ex.code());
        assertTrue(ex.details().stream().anyMatch(d -> d.contains("count")));
    }
}
