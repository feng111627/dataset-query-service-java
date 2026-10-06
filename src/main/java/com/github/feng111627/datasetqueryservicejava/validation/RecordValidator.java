package com.github.feng111627.datasetqueryservicejava.validation;

import com.github.feng111627.datasetqueryservicejava.error.ValidationIssue;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 按 schema 校验记录。
 * 三类问题彼此可区分：
 * - missing_field：必填字段缺失（或值为 null）；
 * - type_mismatch：字段存在但值的类型与声明不符；
 * - unknown_field：出现 schema 未声明的字段。
 * 同一条记录上的多个问题会全部收集（不因首个错误而中断）。
 */
@Component
public class RecordValidator {

    public List<ValidationIssue> validate(DatasetSchema schema, List<DatasetRecord> records) {
        Map<String, FieldDef> declared = new LinkedHashMap<>();
        for (FieldDef field : schema.fields()) {
            declared.put(field.name(), field);
        }

        List<ValidationIssue> issues = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            DatasetRecord record = records.get(i);
            Map<String, Object> values = record == null || record.values() == null
                    ? Map.of() : record.values();

            // 1) 声明字段：缺失 / 类型（按 schema 声明顺序，错误顺序稳定）
            for (FieldDef field : schema.fields()) {
                String name = field.name();
                if (!values.containsKey(name) || values.get(name) == null) {
                    if (field.required()) {
                        issues.add(new ValidationIssue(i, name, "missing_field",
                                "字段 '" + name + "' 为必填项，但记录中缺失或为 null"));
                    }
                    continue;
                }
                if (!typeMatches(field.type(), values.get(name))) {
                    issues.add(new ValidationIssue(i, name, "type_mismatch",
                            "字段 '" + name + "' 声明为 " + field.type()
                                    + "，实际值类型为 " + values.get(name).getClass().getSimpleName()));
                }
            }

            // 2) 未知字段：按字段名排序输出，保证稳定
            TreeSet<String> unknown = new TreeSet<>();
            for (String key : values.keySet()) {
                if (!declared.containsKey(key)) {
                    unknown.add(key);
                }
            }
            for (String key : unknown) {
                issues.add(new ValidationIssue(i, key, "unknown_field",
                        "字段 '" + key + "' 未在 schema 中声明"));
            }
        }
        return issues;
    }

    /**
     * JSON 经 Jackson 反序列化到 Map 后：
     * 整数 -> Integer/Long/BigInteger，浮点 -> Double/BigDecimal，
     * 字符串 -> String，布尔 -> Boolean。
     * 注意 3.0 会被解析为 Double，因此不能赋给 INTEGER 字段。
     */
    private boolean typeMatches(FieldType type, Object value) {
        return switch (type) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Integer
                    || value instanceof Long
                    || value instanceof Short
                    || value instanceof Byte
                    || value instanceof BigInteger;
            case NUMBER -> value instanceof Number;
        };
    }
}
