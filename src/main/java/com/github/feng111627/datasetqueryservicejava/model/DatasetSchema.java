package com.github.feng111627.datasetqueryservicejava.model;

import java.util.List;

/** Declared schema of a dataset. */
public record DatasetSchema(String name, List<FieldDef> fields) {

    public FieldDef field(String fieldName) {
        return fields.stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .orElse(null);
    }
}
