package com.github.feng111627.datasetqueryservicejava.web.dto;

import java.util.List;

/** schema DTO。 */
public class SchemaDto {
    private List<FieldDefDto> fields;

    public List<FieldDefDto> getFields() {
        return fields;
    }

    public void setFields(List<FieldDefDto> fields) {
        this.fields = fields;
    }
}
