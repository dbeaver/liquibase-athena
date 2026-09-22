package liquibase.ext.athena.snapshot;

import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.ViewSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.View;

/**
 * Uses Athena's {@code SHOW CREATE VIEW} support and keeps snapshot generation
 * usable when a view definition cannot be read due to Data Catalog permissions.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class AthenaViewSnapshotGenerator extends ViewSnapshotGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (database instanceof AthenaDatabase) {
            int priority = super.getPriority(objectType, database);
            return priority == PRIORITY_NONE ? PRIORITY_NONE : priority + PRIORITY_DATABASE;
        }
        return PRIORITY_NONE;
    }

    @Override
    public Class<? extends SnapshotGenerator>[] replaces() {
        return new Class[] { ViewSnapshotGenerator.class };
    }

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot) throws DatabaseException {
        try {
            return super.snapshotObject(example, snapshot);
        } catch (DatabaseException e) {
            // SHOW CREATE VIEW requires extra permissions for Data Catalog views.
            // A missing definition must not make the entire schema snapshot fail.
            ((View) example).setDefinition("[CANNOT READ VIEW DEFINITION]");
            return example;
        }
    }
}
