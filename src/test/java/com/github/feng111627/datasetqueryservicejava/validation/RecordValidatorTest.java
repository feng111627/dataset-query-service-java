package com.github.feng111627.datasetqueryservicejava.validation;

import com.github.feng111627.datasetqueryservicejava.error.ValidationIssue;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记录校验：missing_field / type_mismatch / unknown_field 三类原因必须可区分，
 * 且一条记录上的多个问题要全部收集。
 */
class RecordValidatorTest {

    private final RecordValidator validator = new RecordValidator();
    private final DatasetSchema schema = Fixtures.userSchema();

    private List<ValidationIssue> validateSingle(Map<String, Object> values) {
        return validator.validate(schema, List.of(DatasetRecord.input(values)));
    }

    @Nested
    @DisplayName("正常记录")
    class Valid {

        @Test
        @DisplayName("全字段齐全且类型正确时无任何问题")
        void allFieldsValid() {
            List<ValidationIssue> issues = validateSingle(
                    Map.of("id", 1, "name", "alice", "age", 30, "active", true));
            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("可选字段缺省是允许的")
        void optionalFieldsMayBeMissing() {
            List<ValidationIssue> issues = validateSingle(Map.of("id", 2, "name", "bob"));
            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("可选字段显式给 null 不报错")
        void optionalNullAllowed() {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("id", 3);
            values.put("name", "carol");
            values.put("age", null);
            List<ValidationIssue> issues = validateSingle(values);
            assertThat(issues).isEmpty();
        }
    }

    @Nested
    @DisplayName("校验失败：三类原因彼此可区分")
    class Invalid {

        @Test
        @DisplayName("必填字段缺失 -> missing_field，并定位到记录下标和字段名")
        void missingRequiredField() {
            List<ValidationIssue> issues = validateSingle(Map.of("id", 1));
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).kind()).isEqualTo("missing_field");
            assertThat(issues.get(0).field()).isEqualTo("name");
            assertThat(issues.get(0).index()).isZero();
            assertThat(issues.get(0).message()).contains("name");
        }

        @Test
        @DisplayName("必填字段给 null -> missing_field")
        void requiredFieldNull() {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("id", 1);
            values.put("name", null);
            List<ValidationIssue> issues = validateSingle(values);
            assertThat(issues).extracting(ValidationIssue::kind)
                    .containsExactly("missing_field");
        }

        @Test
        @DisplayName("整数字段收到字符串 -> type_mismatch，而不是 missing/unknown")
        void integerFieldGotString() {
            List<ValidationIssue> issues = validateSingle(
                    Map.of("id", "not-a-number", "name", "alice"));
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).kind()).isEqualTo("type_mismatch");
            assertThat(issues.get(0).field()).isEqualTo("id");
        }

        @Test
        @DisplayName("小数值(3.0)不能写入 INTEGER 字段 -> type_mismatch")
        void decimalDoesNotMatchInteger() {
            List<ValidationIssue> issues = validateSingle(
                    Map.of("id", 3.0, "name", "alice"));
            assertThat(issues).extracting(ValidationIssue::kind)
                    .containsExactly("type_mismatch");
        }

        @Test
        @DisplayName("布尔字段收到数字 -> type_mismatch")
        void booleanFieldGotNumber() {
            List<ValidationIssue> issues = validateSingle(
                    Map.of("id", 1, "name", "alice", "active", 1));
            assertThat(issues).extracting(ValidationIssue::kind)
                    .containsExactly("type_mismatch");
        }

        @Test
        @DisplayName("出现未声明字段 -> unknown_field")
        void unknownFieldRejected() {
            List<ValidationIssue> issues = validateSingle(
                    Map.of("id", 1, "name", "alice", "unexpected", "x"));
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).kind()).isEqualTo("unknown_field");
            assertThat(issues.get(0).field()).isEqualTo("unexpected");
        }

        @Test
        @DisplayName("同一条记录同时出现三类问题时全部收集，kind 可区分")
        void multipleIssuesOnOneRecord() {
            // 缺必填 name；id 类型错；多出 unknown 字段
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("id", "bad");
            values.put("extra", 1);
            List<ValidationIssue> issues = validateSingle(values);

            assertThat(issues).hasSize(3);
            assertThat(issues).extracting(ValidationIssue::kind)
                    .containsExactlyInAnyOrder("missing_field", "type_mismatch", "unknown_field");
            assertThat(issues).filteredOn(i -> i.kind().equals("missing_field"))
                    .singleElement()
                    .extracting(ValidationIssue::field).isEqualTo("name");
        }

        @Test
        @DisplayName("批量校验时问题带正确的记录下标")
        void indexPointsToRecordPosition() {
            List<DatasetRecord> records = List.of(
                    DatasetRecord.input(Map.of("id", 1, "name", "ok")),
                    DatasetRecord.input(Map.of("id", 2)) // 缺 name
            );
            List<ValidationIssue> issues = validator.validate(schema, records);
            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).index()).isEqualTo(1);
        }
    }
}
