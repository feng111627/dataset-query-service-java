package com.github.feng111627.datasetqueryservicejava.support;

import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.FieldType;

import java.util.List;

/** 测试夹具：常用 schema。 */
public final class Fixtures {

    private Fixtures() {
    }

    public static DatasetSchema userSchema() {
        return new DatasetSchema(List.of(
                new FieldDef("id", FieldType.INTEGER, true),
                new FieldDef("name", FieldType.STRING, true),
                new FieldDef("age", FieldType.INTEGER, false),
                new FieldDef("active", FieldType.BOOLEAN, false)
        ));
    }
}
