package liquibase.ext.athena.diff;

import liquibase.change.Change;
import liquibase.database.Database;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.diff.output.changelog.core.MissingViewChangeGenerator;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.View;

/**
 * A view generated for another schema has to read that schema instead of the schema it was copied from,
 * and has to be created after the views it reads. The first missing view generates the changes of all views.
 */
public class AthenaMissingViewChangeGenerator extends MissingViewChangeGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        return database instanceof AthenaDatabase && View.class.isAssignableFrom(objectType)
                ? PRIORITY_DATABASE : PRIORITY_NONE;
    }

    @Override
    public Change[] fixMissing(DatabaseObject missingObject, DiffOutputControl control, Database referenceDatabase,
            Database comparisonDatabase, ChangeGeneratorChain chain) {
        return AthenaViewPlan.of((View) missingObject, null, control, referenceDatabase, comparisonDatabase).takeChanges(control);
    }
}
