package com.github.feng111627.datasetqueryservicejava.model;

/** Definition of a single field in a dataset schema. */
public record FieldDef(String name, FieldType type, boolean required) {
}
