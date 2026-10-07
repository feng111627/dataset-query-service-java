package com.github.feng111627.datasetqueryservicejava.schema;

import com.github.feng111627.datasetqueryservicejava.error.ClientException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.model.FieldDef;
import com.github.feng111627.datasetqueryservicejava.model.SchemaChange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility rules for re-registering a schema:
 * <ul>
 *   <li>compatible: identical schema; adding new optional fields;
 *       relaxing a field from required to optional;</li>
 *   <li>incompatible: removing a field; changing a field's type;
 *       adding a new required field; making an optional field required.</li>
 * </ul>
 */
@Component
public class SchemaCompatibility {

    /**
     * Throws ClientException(INCOMPATIBLE_SCHEMA) when the incoming schema
     * is clearly incompatible with the existing one; returns silently otherwise.
     */
    public void checkCompatible(DatasetSchema existing, DatasetSchema incoming) {
        List<String> problems = new ArrayList<>();

        for (FieldDef oldField : existing.fields()) {
            FieldDef newField = incoming.field(oldField.name());
            if (newField == null) {
                problems.add("field '" + oldField.name() + "' was removed");
                continue;
            }
            if (newField.type() != oldField.type()) {
                problems.add("field '" + oldField.name() + "' changed type from "
                        + oldField.type() + " to " + newField.type());
            }
            if (!oldField.required() && newField.required()) {
                problems.add("field '" + oldField.name() + "' changed from optional to required");
            }
        }
        for (FieldDef newField : incoming.fields()) {
            if (existing.field(newField.name()) == null && newField.required()) {
                problems.add("new field '" + newField.name() + "' is required;"
                        + " only optional fields may be added");
            }
        }

        if (!problems.isEmpty()) {
            throw new ClientException(ErrorCode.INCOMPATIBLE_SCHEMA,
                    "schema is not compatible with the existing schema of dataset '"
                            + existing.name() + "'", problems);
        }
    }

    /**
     * Computes the compatible changes from existing to incoming. Must only be
     * called after {@link #checkCompatible} passed. Changes are returned in
     * incoming field order; added fields precede relaxations in that order.
     */
    public List<SchemaChange> diff(DatasetSchema existing, DatasetSchema incoming) {
        List<SchemaChange> changes = new ArrayList<>();
        for (FieldDef newField : incoming.fields()) {
            FieldDef oldField = existing.field(newField.name());
            if (oldField == null) {
                changes.add(new SchemaChange(0, SchemaChange.ChangeType.FIELD_ADDED,
                        newField.name(), newField.type(), newField.required(), null));
            } else if (oldField.required() && !newField.required()) {
                changes.add(new SchemaChange(0, SchemaChange.ChangeType.REQUIRED_TO_OPTIONAL,
                        newField.name(), newField.type(), newField.required(), null));
            }
        }
        return changes;
    }
}
