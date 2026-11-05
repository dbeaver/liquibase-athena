package liquibase.ext.athena.diff;

import liquibase.CatalogAndSchema;
import liquibase.change.Change;
import liquibase.change.core.CreateTableChange;
import liquibase.database.Database;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.diff.output.changelog.core.MissingTableChangeGenerator;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.athena.change.CreateAthenaTableChange;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Table;

/** Generic table metadata does not contain Athena's required S3 LOCATION or storage format. */
public class AthenaMissingTableChangeGenerator extends MissingTableChangeGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        return database instanceof AthenaDatabase && Table.class.isAssignableFrom(objectType)
                ? PRIORITY_DATABASE : PRIORITY_NONE;
    }

    @Override
    public Change[] fixMissing(DatabaseObject missingObject, DiffOutputControl control, Database referenceDatabase,
            Database comparisonDatabase, ChangeGeneratorChain chain) {
        if (!(comparisonDatabase instanceof AthenaDatabase)) {
            return chain.fixMissing(missingObject, control, referenceDatabase, comparisonDatabase);
        }
        Table table = (Table) missingObject;
        String definition;
        try {
            definition = ((AthenaDatabase) referenceDatabase).getTableDefinition(
                    new CatalogAndSchema(table.getSchema().getCatalogName(), table.getSchema().getName()), table.getName());
        } catch (DatabaseException e) {
            throw new UnexpectedLiquibaseException("Cannot read Athena table definition for " + table.getName(), e);
        }
        // Retain column metadata and mark columns as handled using the standard diff logic.
        Change[] changes = super.fixMissing(missingObject, control, referenceDatabase, comparisonDatabase, chain);
        ((CreateAthenaTableChange) changes[0]).setTableDefinition(definition);
        return changes;
    }

    @Override
    protected CreateTableChange createCreateTableChange() {
        return new CreateAthenaTableChange();
    }
}
