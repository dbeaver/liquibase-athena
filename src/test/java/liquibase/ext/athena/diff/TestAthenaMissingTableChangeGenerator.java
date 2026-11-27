package liquibase.ext.athena.diff;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import liquibase.CatalogAndSchema;
import liquibase.change.Change;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.CreateViewChange;
import liquibase.database.core.H2Database;
import liquibase.diff.DiffResult;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGenerator;
import liquibase.diff.output.changelog.ChangeGeneratorFactory;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.athena.change.CreateAthenaTableChange;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.EmptyDatabaseSnapshot;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.structure.core.Column;
import liquibase.structure.core.DataType;
import liquibase.structure.core.Table;
import liquibase.structure.core.View;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestAthenaMissingTableChangeGenerator {
    private static final String DEFINITION = "CREATE EXTERNAL TABLE `source`.`events` (`id` bigint)\n"
            + "STORED AS PARQUET\nLOCATION 's3://bucket/source/events/'";

    @Test
    public void registeredGeneratorReadsTheSourceAndAllowsTargetSchemaRemapping() {
        AtomicReference<CatalogAndSchema> requestedSchema = new AtomicReference<>();
        AthenaDatabase source = new AthenaDatabase() {
            @Override
            public String getTableDefinition(CatalogAndSchema schema, String tableName) {
                requestedSchema.set(schema);
                assertEquals("events", tableName);
                return DEFINITION;
            }
        };
        Table table = table();
        DiffOutputControl control = new DiffOutputControl(true, true, false, null);
        Change[] changes = ChangeGeneratorFactory.getInstance().fixMissing(table, control, source, new AthenaDatabase());

        assertEquals(1, changes.length);
        assertTrue(changes[0] instanceof CreateTableChange);
        CreateTableChange change = (CreateTableChange) changes[0];
        assertEquals("source", requestedSchema.get().getSchemaName());
        assertEquals("AwsDataCatalog", requestedSchema.get().getCatalogName());
        assertEquals("source", change.getSchemaName());
        assertEquals("id", change.getColumns().get(0).getName());
        assertTrue(control.alreadyHandledMissing(table.getColumns().get(0), source));
        // Schema comparison remaps the change after generating it, before generating SQL.
        change.setSchemaName("target");
        AthenaDatabase target = new AthenaDatabase();
        assertEquals("CREATE EXTERNAL TABLE `target`.`events` (`id` bigint)\n"
                + "STORED AS PARQUET\nLOCATION 's3://bucket/source/events/'",
                SqlGeneratorFactory.getInstance().generateSql(change.generateStatements(target)[0], target)[0].toSql());
    }

    @Test
    public void diffToChangelogDoesNotEmitDuplicateColumnChanges() throws Exception {
        AthenaDatabase source = new AthenaDatabase() {
            @Override
            public String getTableDefinition(CatalogAndSchema schema, String tableName) {
                return DEFINITION;
            }
        };
        Table table = table();
        DiffResult result = new DiffResult(new EmptyDatabaseSnapshot(source),
                new EmptyDatabaseSnapshot(new AthenaDatabase()), new CompareControl());
        result.addMissingObject(table);
        table.getColumns().forEach(result::addMissingObject);

        var changeSets = new DiffToChangeLog(result, new DiffOutputControl(true, true, false, null)).generateChangeSets();

        assertEquals(1, changeSets.size());
        assertEquals(1, changeSets.get(0).getChanges().size());
        assertTrue(changeSets.get(0).getChanges().get(0) instanceof CreateAthenaTableChange);
    }

    @Test
    public void createsMissingTablesBeforeReplacedAndDependentViews() throws Exception {
        var comparisons = new CompareControl.SchemaComparison[] { new CompareControl.SchemaComparison(
                new CatalogAndSchema("AwsDataCatalog", "source"), new CatalogAndSchema("AwsDataCatalog", "target")) };
        for (boolean missingViews : new boolean[] { false, true }) {
            AthenaDatabase source = new AthenaDatabase() {
                @Override
                public String getTableDefinition(CatalogAndSchema schema, String tableName) {
                    return DEFINITION;
                }
            };
            source.setDefaultCatalogName("AwsDataCatalog");
            AthenaDatabase target = new AthenaDatabase();
            target.setDefaultCatalogName("AwsDataCatalog");
            DiffResult result = new DiffResult(new EmptyDatabaseSnapshot(source), new EmptyDatabaseSnapshot(target),
                    new CompareControl(comparisons, "tables,views,columns"));
            Table table = table();
            result.addMissingObject(table);
            table.getColumns().forEach(result::addMissingObject);
            View replaced = new View("AwsDataCatalog", "source", "z_view");
            replaced.setDefinition("SELECT id, 1 AS extra FROM source.events");
            result.addChangedObject(replaced, new ObjectDifferences(new CompareControl())
                    .addDifference("definition", replaced.getDefinition(), "SELECT id FROM target.events"));
            if (missingViews) {
                View dependent = new View("AwsDataCatalog", "source", "a_view");
                dependent.setDefinition("SELECT id, extra FROM source.z_view");
                result.addMissingObject(dependent);
                View readsTable = new View("AwsDataCatalog", "source", "b_view");
                readsTable.setDefinition("SELECT id FROM source.events");
                result.addMissingObject(readsTable);
            }

            List<Change> changes = new DiffToChangeLog(result, new DiffOutputControl(true, true, false, comparisons))
                    .generateChangeSets().stream().flatMap(changeSet -> changeSet.getChanges().stream()).toList();

            assertTrue(changes.get(0) instanceof CreateAthenaTableChange);
            assertEquals(missingViews ? List.of("events", "b_view", "z_view", "a_view") : List.of("events", "z_view"),
                    changes.stream().map(change -> change instanceof CreateTableChange created ? created.getTableName()
                            : ((CreateViewChange) change).getViewName()).toList());
        }
    }

    @Test
    public void doesNotChangeOtherDatabaseDiffsOrCrossDatabaseMigration() {
        AthenaMissingTableChangeGenerator generator = new AthenaMissingTableChangeGenerator();
        assertEquals(ChangeGenerator.PRIORITY_NONE, generator.getPriority(Table.class, new H2Database()));
        assertEquals(ChangeGenerator.PRIORITY_NONE, generator.getPriority(View.class, new AthenaDatabase()));

        // A non-Athena target must use generic metadata, never Athena native DDL.
        Change[] changes = ChangeGeneratorFactory.getInstance().fixMissing(table(),
                new DiffOutputControl(true, true, false, null), new AthenaDatabase(), new H2Database());
        assertEquals(CreateTableChange.class, changes[0].getClass());
        Change[] h2Changes = ChangeGeneratorFactory.getInstance().fixMissing(table(),
                new DiffOutputControl(true, true, false, null), new H2Database(), new H2Database());
        assertEquals(CreateTableChange.class, h2Changes[0].getClass());
        Change[] toAthena = ChangeGeneratorFactory.getInstance().fixMissing(table(),
                new DiffOutputControl(true, true, false, null), new H2Database(), new AthenaDatabase());
        assertEquals(CreateTableChange.class, toAthena[0].getClass());
        assertEquals("events", ((CreateTableChange) toAthena[0]).getTableName());
        assertEquals("id", ((CreateTableChange) toAthena[0]).getColumns().get(0).getName());
    }

    @Test
    public void reportsReadFailuresRatherThanGeneratingIncompleteSql() {
        DatabaseException denied = new DatabaseException("SHOW CREATE TABLE denied");
        AthenaDatabase source = new AthenaDatabase() {
            @Override
            public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
                throw denied;
            }
        };
        Table table = table();
        DiffOutputControl control = new DiffOutputControl(true, true, false, null);

        UnexpectedLiquibaseException error = assertThrows(UnexpectedLiquibaseException.class,
                () -> ChangeGeneratorFactory.getInstance().fixMissing(table, control, source, new AthenaDatabase()));

        assertSame(denied, error.getCause());
        assertFalse(control.alreadyHandledMissing(table.getColumns().get(0), source));
    }

    private static Table table() {
        Table table = new Table("AwsDataCatalog", "source", "events");
        Column column = new Column().setName("id").setType(new DataType("BIGINT")).setRelation(table);
        table.getColumns().add(column);
        return table;
    }
}
