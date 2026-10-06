package com.github.feng111627.datasetqueryservicejava.tests;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.schema.SchemaValidator;
import com.github.feng111627.datasetqueryservicejava.tests.support.BusinessCoverageLogger;
import com.github.feng111627.datasetqueryservicejava.tests.support.CoversScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@CoversScenario("record validation: missing fields, type mismatches and unknown fields are rejected with distinguishable error codes")
@ExtendWith(BusinessCoverageLogger.class)
class SchemaValidationTest {

    private final SchemaValidator validator = new SchemaValidator();

    private final DatasetSchema schema = new DatasetSchema("events", List.of(
            new FieldDef("user", FieldType.STRING, true),
            new FieldDef("amount", FieldType.DOUBLE, true),
            new FieldDef("count", FieldType.INTEGER, false),
            new FieldDef("active", FieldType.BOOLEAN, false)
    ));

    @Test
    @DisplayName("a fully valid record is accepted")
    void validRecordAccepted() {
        assertDoesNotThrow(() -> validator.validate(schema,
                Map.of("user", "alice", "amount", 9.5, "count", 3, "active", true)));
    }

    @Test
    @DisplayName("missing required field -> MISSING_FIELD")
    void missingRequiredField() {
        ClientException ex = assertThrows(ClientException.class,
                () -> validator.validate(schema, Map.of("amount", 1.0)));
        assertEquals(ErrorCode.MISSING_FIELD, ex.code());
        assertTrue(ex.details().contains("user"));
    }

    @Test
    @DisplayName("absent optional field is accepted")
    void optionalFieldMayBeAbsent() {
        assertDoesNotThrow(() -> validator.validate(schema,
                Map.of("user", "bob", "amount", 2)));
    }

    @Test
    @DisplayName("wrong value type -> TYPE_MISMATCH")
    void typeMismatch() {
        ClientException ex = assertThrows(ClientException.class,
                () -> validator.validate(schema, Map.of("user", "bob", "amount", "not-a-number")));
        assertEquals(ErrorCode.TYPE_MISMATCH, ex.code());
        assertTrue(ex.details().get(0).contains("amount"));
    }

    @Test
    @DisplayName("fractional value for INTEGER field -> TYPE_MISMATCH")
    void fractionalIntegerRejected() {
        ClientException ex = assertThrows(ClientException.class,
                () -> validator.validate(schema,
                        Map.of("user", "bob", "amount", 1, "count", 1.5)));
        assertEquals(ErrorCode.TYPE_MISMATCH, ex.code());
    }

    @Test
    @DisplayName("undeclared field -> UNKNOWN_FIELD")
    void unknownField() {
        ClientException ex = assertThrows(ClientException.class,
                () -> validator.validate(schema,
                        Map.of("user", "bob", "amount", 1, "surprise", 42)));
        assertEquals(ErrorCode.UNKNOWN_FIELD, ex.code());
        assertTrue(ex.details().contains("surprise"));
    }

    @Test
    @DisplayName("error codes are distinguishable across the three failure categories")
    void errorCodesAreDistinguishable() {
        ClientException missing = assertThrows(ClientException.class,
                () -> validator.validate(schema, Map.of("amount", 1)));
        ClientException mismatch = assertThrows(ClientException.class,
                () -> validator.validate(schema, Map.of("user", 1, "amount", 1)));
        ClientException unknown = assertThrows(ClientException.class,
                () -> validator.validate(schema, Map.of("user", "x", "amount", 1, "zzz", 0)));
        assertEquals(ErrorCode.MISSING_FIELD, missing.code());
        assertEquals(ErrorCode.TYPE_MISMATCH, mismatch.code());
        assertEquals(ErrorCode.UNKNOWN_FIELD, unknown.code());
        assertNotEquals(missing.code(), mismatch.code());
        assertNotEquals(mismatch.code(), unknown.code());
    }
}
