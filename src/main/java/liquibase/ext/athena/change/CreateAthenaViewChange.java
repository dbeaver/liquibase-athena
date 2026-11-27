package liquibase.ext.athena.change;

import liquibase.change.ChangeMetaData;
import liquibase.change.DatabaseChange;
import liquibase.change.DatabaseChangeProperty;
import liquibase.change.core.CreateViewChange;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;

/**
 * A view generated from a view of another schema. Validation reports why it cannot be created there:
 * its query could not be made to read that schema, or it is built on such a view.
 */
@DatabaseChange(name = "createAthenaView", description = "Creates an Athena view",
        priority = ChangeMetaData.PRIORITY_DEFAULT, appliesTo = "view")
public class CreateAthenaViewChange extends CreateViewChange {
    private String referenceError;

    /** The reason why the view cannot be created, or null. */
    @DatabaseChangeProperty(description = "Reason why the generated view cannot be migrated")
    public String getReferenceError() {
        return referenceError;
    }

    public void rejectReferences(String reason) {
        referenceError = reason;
    }

    public void setReferenceError(String referenceError) {
        this.referenceError = referenceError;
    }

    // Valid views keep the standard format; rejected views must preserve their validation error.
    @Override
    public String getSerializedObjectName() {
        return referenceError == null ? "createView" : super.getSerializedObjectName();
    }

    @Override
    public String getSerializedObjectNamespace() {
        return referenceError == null ? STANDARD_CHANGELOG_NAMESPACE : GENERIC_CHANGELOG_EXTENSION_NAMESPACE;
    }

    @Override
    public ValidationErrors validate(Database database) {
        ValidationErrors errors = super.validate(database);
        if (referenceError != null) {
            errors.addError(referenceError);
        }
        return errors;
    }
}
