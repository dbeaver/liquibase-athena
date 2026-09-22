package liquibase.ext.athena.snapshot;

import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.InvalidExampleException;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.structure.DatabaseObject;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class AthenaForeignKeySnapshotGenerator extends liquibase.snapshot.jvm.ForeignKeySnapshotGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (database instanceof AthenaDatabase) {
            int priority = super.getPriority(objectType, database);
            return priority == PRIORITY_NONE ? PRIORITY_NONE : priority + PRIORITY_DATABASE;
        }
        return PRIORITY_NONE;
    }

    @Override
    protected void addTo(DatabaseObject foundObject, DatabaseSnapshot snapshot)
            throws DatabaseException, InvalidExampleException {
        // Athena does not support calls to MetaData.getIncomingKeys() and doesn't
        // really support FKs anyway
    }

    @Override
    public Class<? extends SnapshotGenerator>[] replaces() {
        return new Class[] { liquibase.snapshot.jvm.ForeignKeySnapshotGenerator.class };
    }

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot)
            throws DatabaseException, InvalidExampleException {
        return null; // Athena does not support calls to MetaData.getIncomingKeys() and doesn't
                     // really support FKs anyway
    }
}
