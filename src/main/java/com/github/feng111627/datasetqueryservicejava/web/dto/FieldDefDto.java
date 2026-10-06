package com.github.feng111627.datasetqueryservicejava.web.dto;

/** 字段声明 DTO。required 缺省为 true。 */
public class FieldDefDto {
    private String name;
    private String type;
    private Boolean required = Boolean.TRUE;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }
}
