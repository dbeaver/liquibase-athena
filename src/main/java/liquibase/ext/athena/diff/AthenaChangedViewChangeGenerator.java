package liquibase.ext.athena.diff;

import liquibase.change.Change;
import liquibase.change.core.CreateViewChange;
import liquibase.database.Database;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.diff.output.changelog.core.ChangedViewChangeGenerator;
import liquibase.ext.athena.change.CreateAthenaViewChange;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.View;

/** A view replaced by a view of another schema has to read its own schema, like a missing one. */
public class AthenaChangedViewChangeGenerator extends ChangedViewChangeGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        return database instanceof AthenaDatabase && View.class.isAssignableFrom(objectType)
                ? PRIORITY_DATABASE : PRIORITY_NONE;
    }

    @Override
    public Change[] fixChanged(DatabaseObject changedObject, ObjectDifferences differences, DiffOutputControl control,
            Database referenceDatabase, Database comparisonDatabase, ChangeGeneratorChain chain) {
        Change[] changes = super.fixChanged(changedObject, differences, control, referenceDatabase, comparisonDatabase, chain);
        AthenaMissingViewChangeGenerator.mapReferences(changes, control, referenceDatabase, comparisonDatabase);
        return changes;
    }

    @Override
    protected CreateViewChange createViewChange() {
        return new CreateAthenaViewChange();
    }
}
