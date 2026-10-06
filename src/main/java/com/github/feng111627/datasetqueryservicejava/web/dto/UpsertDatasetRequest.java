package com.github.feng111627.datasetqueryservicejava.web.dto;

import java.util.List;
import java.util.Map;

/** PUT /datasets/{name} 请求体：可兼容更新 schema，并可同时写入首批记录。 */
public class UpsertDatasetRequest {
    private SchemaDto schema;
    private List<Map<String, Object>> records;

    public SchemaDto getSchema() {
        return schema;
    }

    public void setSchema(SchemaDto schema) {
        this.schema = schema;
    }

    public List<Map<String, Object>> getRecords() {
        return records;
    }

    public void setRecords(List<Map<String, Object>> records) {
        this.records = records;
    }
}
