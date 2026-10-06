package com.github.feng111627.datasetqueryservicejava.web;

import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;
import com.github.feng111627.datasetqueryservicejava.web.dto.FieldDefDto;
import com.github.feng111627.datasetqueryservicejava.web.dto.SchemaDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** DTO 与领域模型互转；所有输入结构问题统一转为 {@link ApiException}，不抛底层解析异常。 */
@Component
public class DtoMapper {

    public DatasetSchema toSchema(SchemaDto dto) {
        if (dto == null || dto.getFields() == null || dto.getFields().isEmpty()) {
            throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                    "请求必须包含非空的 schema.fields 列表");
        }
        List<FieldDef> fields = new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int i = 0; i < dto.getFields().size(); i++) {
            FieldDefDto field = dto.getFields().get(i);
            if (field == null) {
                throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                        "schema.fields[" + i + "] 不能为空");
            }
            String name = field.getName();
            if (name == null || name.isBlank()) {
                throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                        "schema.fields[" + i + "].name 不能为空");
            }
            if (!names.add(name)) {
                throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                        "schema 中字段名 '" + name + "' 重复");
            }
            FieldType type;
            try {
                type = FieldType.valueOf(String.valueOf(field.getType()).trim().toUpperCase());
            } catch (RuntimeException ex) {
                throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                        "字段 '" + name + "' 的类型 '" + field.getType()
                                + "' 不支持，允许的值为 STRING/INTEGER/NUMBER/BOOLEAN");
            }
            boolean required = field.getRequired() == null || field.getRequired();
            fields.add(new FieldDef(name, type, required));
        }
        return new DatasetSchema(List.copyOf(fields));
    }

    public SchemaDto toSchemaDto(DatasetSchema schema) {
        SchemaDto dto = new SchemaDto();
        List<FieldDefDto> fieldDtos = new ArrayList<>();
        for (FieldDef field : schema.fields()) {
            FieldDefDto fieldDto = new FieldDefDto();
            fieldDto.setName(field.name());
            fieldDto.setType(field.type().name());
            fieldDto.setRequired(field.required());
            fieldDtos.add(fieldDto);
        }
        dto.setFields(fieldDtos);
        return dto;
    }

    public List<DatasetRecord> toRecords(List<Map<String, Object>> raw) {
        if (raw == null) {
            throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                    "请求必须包含 records 列表");
        }
        if (raw.isEmpty()) {
            throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                    "records 不能为空");
        }
        List<DatasetRecord> records = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> values = raw.get(i);
            if (values == null) {
                throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                        "records[" + i + "] 不能为空");
            }
            records.add(DatasetRecord.input(values));
        }
        return records;
    }
}
