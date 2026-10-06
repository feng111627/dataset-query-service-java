package com.github.feng111627.datasetqueryservicejava.validation;

import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重复注册时的 schema 兼容性判断（面向“已有存量数据”的安全演进）：
 * <ul>
 *   <li>已有字段不得删除（存量记录仍带该列）；</li>
 *   <li>已有字段类型不得变更；</li>
 *   <li>已有必填字段放宽为可选：兼容；但可选字段收紧为必填：不兼容（存量记录可能缺值）；</li>
 *   <li>只允许新增“可选”字段（存量记录没有新列，新增必填列会让存量数据失效）。</li>
 * </ul>
 * 字段顺序调整不影响存量数据，视为兼容。
 */
public final class SchemaCompatibility {

    private SchemaCompatibility() {
    }

    public record Result(boolean compatible, String reason) {
    }

    public static Result check(DatasetSchema existing, DatasetSchema incoming) {
        Map<String, FieldDef> oldFields = index(existing);
        Map<String, FieldDef> newFields = index(incoming);

        for (Map.Entry<String, FieldDef> entry : oldFields.entrySet()) {
            String name = entry.getKey();
            FieldDef oldField = entry.getValue();
            FieldDef newField = newFields.get(name);
            if (newField == null) {
                return new Result(false, "字段 '" + name + "' 已存在于旧 schema 中，删除字段属于不兼容变更");
            }
            if (newField.type() != oldField.type()) {
                return new Result(false, "字段 '" + name + "' 的类型由 " + oldField.type()
                        + " 变为 " + newField.type() + "，类型变更属于不兼容变更");
            }
            if (!oldField.required() && newField.required()) {
                return new Result(false, "字段 '" + name
                        + "' 由可选收紧为必填，存量记录可能缺少该字段，属于不兼容变更");
            }
        }

        for (Map.Entry<String, FieldDef> entry : newFields.entrySet()) {
            String name = entry.getKey();
            if (!oldFields.containsKey(name) && entry.getValue().required()) {
                return new Result(false, "新增字段 '" + name
                        + "' 为必填，存量记录没有该字段，属于不兼容变更（新增字段必须可选）");
            }
        }
        return new Result(true, null);
    }

    private static Map<String, FieldDef> index(DatasetSchema schema) {
        Map<String, FieldDef> map = new LinkedHashMap<>();
        for (FieldDef field : schema.fields()) {
            map.put(field.name(), field);
        }
        return map;
    }
}
