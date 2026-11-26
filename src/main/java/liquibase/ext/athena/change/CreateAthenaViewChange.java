package liquibase.ext.athena.change;

import liquibase.change.core.CreateViewChange;
import liquibase.database.Database;
import liquibase.exception.ValidationErrors;

/**
 * A view generated from a view of another schema. Validation reports why it cannot be created there:
 * its query could not be made to read that schema, or it is built on such a view.
 */
public class CreateAthenaViewChange extends CreateViewChange {
    private String referenceError;

    /** The reason why the view cannot be created, or null. */
    public String getReferenceError() {
        return referenceError;
    }

    public void rejectReferences(String reason) {
        referenceError = reason;
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
