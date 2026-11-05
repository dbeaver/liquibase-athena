package liquibase.ext.athena.snapshot;

import liquibase.database.Database;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.CachedRow;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.ViewSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;
import liquibase.structure.core.View;
import liquibase.util.StringUtil;

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
        if (((View) example).getDefinition() != null) {
            return example;
        }
        View view = null;
        for (CachedRow row : AthenaRelationMetadata.getRelations(snapshot, example.getSchema(), true)) {
            if (example.getName().equalsIgnoreCase(row.getString("TABLE_NAME"))) {
                view = new View().setName(cleanNameFromDatabase(row.getString("TABLE_NAME"), snapshot.getDatabase()));
                view.setSchema(example.getSchema());
                view.setRemarks(row.getString("REMARKS"));
                break;
            }
        }
        if (view == null) {
            return null;
        }
        Database database = snapshot.getDatabase();
        ObjectQuotingStrategy originalStrategy = database.getObjectQuotingStrategy();
        try {
            database.setObjectQuotingStrategy(ObjectQuotingStrategy.QUOTE_ALL_OBJECTS);
            String definition = snapshot.getDatabase().getViewDefinition(view.getSchema().toCatalogAndSchema(), view.getName());
            view.setDefinition(StringUtil.trimToNull(definition) == null ? "[CANNOT READ VIEW DEFINITION]" : definition);
        } catch (DatabaseException e) {
            // SHOW CREATE VIEW requires extra permissions for Data Catalog views.
            // A missing definition must not make the entire schema snapshot fail.
            view.setDefinition("[CANNOT READ VIEW DEFINITION]");
        } finally {
            database.setObjectQuotingStrategy(originalStrategy);
        }
        return view;
    }

    @Override
    protected void addTo(DatabaseObject foundObject, DatabaseSnapshot snapshot) throws DatabaseException {
        if (foundObject instanceof Schema schema && snapshot.getSnapshotControl().shouldInclude(View.class)) {
            for (CachedRow row : AthenaRelationMetadata.getRelations(snapshot, schema, true)) {
                schema.addDatabaseObject(new View().setName(cleanNameFromDatabase(row.getString("TABLE_NAME"), snapshot.getDatabase()))
                        .setSchema(schema));
            }
        }
    }
}
