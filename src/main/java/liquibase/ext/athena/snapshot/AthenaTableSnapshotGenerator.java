package liquibase.ext.athena.snapshot;

import java.sql.SQLException;

import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.CachedRow;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.TableSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;

/** Includes legacy Simba external/managed tables as well as standard JDBC tables. */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class AthenaTableSnapshotGenerator extends TableSnapshotGenerator {
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
        return new Class[] { TableSnapshotGenerator.class };
    }

    @Override
    protected void addTo(DatabaseObject foundObject, DatabaseSnapshot snapshot) throws DatabaseException {
        if (foundObject instanceof Schema schema && snapshot.getSnapshotControl().shouldInclude(Table.class)) {
            for (CachedRow row : AthenaRelationMetadata.getRelations(snapshot, schema, false)) {
                schema.addDatabaseObject(new Table().setName(cleanNameFromDatabase(row.getString("TABLE_NAME"), snapshot.getDatabase()))
                        .setSchema(schema));
            }
        }
    }

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot) throws DatabaseException {
        for (CachedRow row : AthenaRelationMetadata.getRelations(snapshot, example.getSchema(), false)) {
            if (example.getName().equalsIgnoreCase(row.getString("TABLE_NAME"))) {
                try {
                    return readTable(row, snapshot.getDatabase());
                } catch (SQLException e) {
                    throw new DatabaseException(e);
                }
            }
        }
        return null;
    }
}
