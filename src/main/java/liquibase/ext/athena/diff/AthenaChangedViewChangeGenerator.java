package liquibase.ext.athena.diff;

import liquibase.change.Change;
import liquibase.database.Database;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.diff.output.changelog.core.ChangedViewChangeGenerator;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.View;

/**
 * A view which differs from the reference one only by the names of the compared schemas is left alone.
 * When a view is replaced, the first changed view generates the changes of all views, see {@link AthenaViewPlan}.
 */
public class AthenaChangedViewChangeGenerator extends ChangedViewChangeGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        return database instanceof AthenaDatabase && View.class.isAssignableFrom(objectType)
                ? PRIORITY_DATABASE : PRIORITY_NONE;
    }

    @Override
    public Change[] fixChanged(DatabaseObject changedObject, ObjectDifferences differences, DiffOutputControl control,
            Database referenceDatabase, Database comparisonDatabase, ChangeGeneratorChain chain) {
        if (!(comparisonDatabase instanceof AthenaDatabase)) {
            return chain.fixChanged(changedObject, differences, control, referenceDatabase, comparisonDatabase);
        }
        AthenaViewPlan plan = AthenaViewPlan.of((View) changedObject, differences, control, referenceDatabase, comparisonDatabase);
        // Liquibase visits changed objects first, but emits its create batch before its update batch.
        // Keep dependent views together in the update batch when any existing view must be replaced.
        return plan.replacesViews() ? plan.takeChanges(control) : null;
    }
}
