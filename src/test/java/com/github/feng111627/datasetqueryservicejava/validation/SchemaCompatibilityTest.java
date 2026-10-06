package com.github.feng111627.datasetqueryservicejava.validation;

import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema 演进兼容性：
 * 允许新增可选字段、必填放宽为可选；拒绝删字段、改类型、可选收紧必填、新增必填字段。
 */
class SchemaCompatibilityTest {

    private FieldDef field(String name, FieldType type, boolean required) {
        return new FieldDef(name, type, required);
    }

    private final DatasetSchema base = new DatasetSchema(List.of(
            field("id", FieldType.INTEGER, true),
            field("name", FieldType.STRING, true),
            field("age", FieldType.INTEGER, false)
    ));

    @Test
    @DisplayName("完全相同的 schema 重复注册：兼容")
    void identicalSchemaIsCompatible() {
        SchemaCompatibility.Result result = SchemaCompatibility.check(base, new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.STRING, true),
                field("age", FieldType.INTEGER, false)
        )));
        assertThat(result.compatible()).isTrue();
    }

    @Test
    @DisplayName("新增可选字段：兼容")
    void addingOptionalFieldIsCompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.STRING, true),
                field("age", FieldType.INTEGER, false),
                field("city", FieldType.STRING, false)
        ));
        assertThat(SchemaCompatibility.check(base, evolved).compatible()).isTrue();
    }

    @Test
    @DisplayName("必填字段放宽为可选：兼容")
    void relaxingRequiredToOptionalIsCompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.STRING, false),
                field("age", FieldType.INTEGER, false)
        ));
        assertThat(SchemaCompatibility.check(base, evolved).compatible()).isTrue();
    }

    @Test
    @DisplayName("删除已有字段：不兼容，原因指向被删字段")
    void droppingFieldIsIncompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.STRING, true)
        ));
        SchemaCompatibility.Result result = SchemaCompatibility.check(base, evolved);
        assertThat(result.compatible()).isFalse();
        assertThat(result.reason()).contains("age").contains("删除");
    }

    @Test
    @DisplayName("字段类型变更 STRING->INTEGER：不兼容")
    void changingTypeIsIncompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.INTEGER, true),
                field("age", FieldType.INTEGER, false)
        ));
        SchemaCompatibility.Result result = SchemaCompatibility.check(base, evolved);
        assertThat(result.compatible()).isFalse();
        assertThat(result.reason()).contains("name").contains("类型");
    }

    @Test
    @DisplayName("可选字段收紧为必填：不兼容（存量记录可能缺值）")
    void tighteningOptionalToRequiredIsIncompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.STRING, true),
                field("age", FieldType.INTEGER, true)
        ));
        SchemaCompatibility.Result result = SchemaCompatibility.check(base, evolved);
        assertThat(result.compatible()).isFalse();
        assertThat(result.reason()).contains("age").contains("必填");
    }

    @Test
    @DisplayName("新增必填字段：不兼容（存量记录没有该列）")
    void addingRequiredFieldIsIncompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("id", FieldType.INTEGER, true),
                field("name", FieldType.STRING, true),
                field("age", FieldType.INTEGER, false),
                field("city", FieldType.STRING, true)
        ));
        SchemaCompatibility.Result result = SchemaCompatibility.check(base, evolved);
        assertThat(result.compatible()).isFalse();
        assertThat(result.reason()).contains("city");
    }

    @Test
    @DisplayName("字段顺序调整：兼容（顺序不影响存量数据）")
    void reorderingFieldsIsCompatible() {
        DatasetSchema evolved = new DatasetSchema(List.of(
                field("name", FieldType.STRING, true),
                field("age", FieldType.INTEGER, false),
                field("id", FieldType.INTEGER, true)
        ));
        assertThat(SchemaCompatibility.check(base, evolved).compatible()).isTrue();
    }
}
