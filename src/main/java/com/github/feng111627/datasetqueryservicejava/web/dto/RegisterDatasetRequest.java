package com.github.feng111627.datasetqueryservicejava.web.dto;

/** POST /datasets 请求体。 */
public class RegisterDatasetRequest {
    private String name;
    private SchemaDto schema;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SchemaDto getSchema() {
        return schema;
    }

    public void setSchema(SchemaDto schema) {
        this.schema = schema;
    }
}
