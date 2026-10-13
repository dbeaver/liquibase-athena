package liquibase.ext.athena.database;

import java.lang.reflect.Proxy;
import java.util.List;

import liquibase.change.core.CreateViewChange;
import liquibase.database.DatabaseConnection;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import org.junit.Test;

import liquibase.database.DatabaseFactory;
import liquibase.database.core.H2Database;
import liquibase.ext.athena.snapshot.AthenaForeignKeySnapshotGenerator;
import liquibase.ext.athena.snapshot.AthenaSchemaSnapshotGenerator;
import liquibase.ext.athena.snapshot.AthenaViewSnapshotGenerator;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Sequence;
import liquibase.structure.core.Table;
import liquibase.structure.core.UniqueConstraint;
import liquibase.structure.core.View;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestAthenaDatabase {
    private static final String DRIVER_CLASS = "com.amazon.athena.jdbc.AthenaDriver";

    @Test
    public void findsDriverForCurrentAndLegacyUrls() {
        assertEquals(DRIVER_CLASS, DatabaseFactory.getInstance().findDefaultDriver(
                "jdbc:athena://Region=us-east-1;OutputLocation=s3://bucket/results;"));
        assertEquals(DRIVER_CLASS, DatabaseFactory.getInstance().findDefaultDriver(
                "jdbc:awsathena://Region=us-east-1;OutputLocation=s3://bucket/results;"));
    }

    @Test
    public void detectsAthenaByUrlOrProductName() throws Exception {
        AthenaDatabase database = new AthenaDatabase();

        assertTrue(database.isCorrectDatabaseImplementation(connection("jdbc:athena://Region=us-east-1;", "unknown", null)));
        assertTrue(database.isCorrectDatabaseImplementation(connection("jdbc:other:", "Amazon Athena", null)));
        assertFalse(database.isCorrectDatabaseImplementation(connection("jdbc:h2:mem:test", "H2", null)));
    }

    @Test
    public void readsDefaultCatalogAndSchemaFromConnection() {
        AthenaDatabase database = new AthenaDatabase();
        database.setConnection(connection(
                "jdbc:athena://Region=us-east-1;Catalog=AwsDataCatalog;Database=analytics;",
                "Amazon Athena",
                null));

        assertEquals("AwsDataCatalog", database.getDefaultCatalogName());
        assertEquals("analytics", database.getDefaultSchemaName());
    }

    @Test
    public void supportsSchemaCompareObjectsOnly() {
        AthenaDatabase database = new AthenaDatabase();

        assertTrue(database.supports(Schema.class));
        assertTrue(database.supports(Table.class));
        assertFalse(database.supports(Sequence.class));
        assertFalse(database.supports(Index.class));
        assertFalse(database.supports(PrimaryKey.class));
        assertFalse(database.supports(ForeignKey.class));
        assertFalse(database.supports(UniqueConstraint.class));
    }

    @Test
    public void snapshotReplacementsOnlyApplyToAthena() {
        AthenaDatabase athena = new AthenaDatabase();
        H2Database h2 = new H2Database();
        AthenaForeignKeySnapshotGenerator foreignKeys = new AthenaForeignKeySnapshotGenerator();
        AthenaSchemaSnapshotGenerator schemas = new AthenaSchemaSnapshotGenerator();
        AthenaViewSnapshotGenerator views = new AthenaViewSnapshotGenerator();

        assertTrue(foreignKeys.getPriority(ForeignKey.class, athena) > SnapshotGenerator.PRIORITY_NONE);
        assertEquals(SnapshotGenerator.PRIORITY_NONE, foreignKeys.getPriority(ForeignKey.class, h2));
        assertTrue(schemas.getPriority(Schema.class, athena) > SnapshotGenerator.PRIORITY_NONE);
        assertEquals(SnapshotGenerator.PRIORITY_NONE, schemas.getPriority(Schema.class, h2));
        assertEquals(SnapshotGenerator.PRIORITY_NONE, schemas.getPriority(Table.class, athena));
        assertEquals(SnapshotGenerator.PRIORITY_NONE, foreignKeys.getPriority(Schema.class, athena));
        assertTrue(views.getPriority(View.class, athena) > SnapshotGenerator.PRIORITY_NONE);
        assertEquals(SnapshotGenerator.PRIORITY_NONE, views.getPriority(View.class, h2));
        assertEquals(SnapshotGenerator.PRIORITY_NONE, views.getPriority(Table.class, athena));
    }

    @Test
    public void extractsQueryFromMultiRowViewDefinitions() {
        assertEquals("SELECT id\nFROM analytics.events", AthenaDatabase.formatViewDefinition(List.of(
                "CREATE VIEW analytics.report AS", "SELECT id", "FROM analytics.events")));
        assertEquals("SELECT id FROM analytics.events", AthenaDatabase.formatViewDefinition(List.of(
                "CREATE VIEW analytics.report AS SELECT id FROM analytics.events")));
        assertNull(AthenaDatabase.formatViewDefinition(List.of()));
    }

    @Test
    public void generatesViewDdlForTargetSchema() {
        CreateViewChange change = new CreateViewChange();
        change.setSchemaName("target");
        change.setViewName("report");
        change.setSelectQuery(AthenaDatabase.formatViewDefinition(List.of(
                "CREATE VIEW source.report AS", "SELECT id FROM source.events")));

        SqlStatement statement = change.generateStatements(new AthenaDatabase())[0];
        Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statement, new AthenaDatabase());

        assertEquals("CREATE VIEW \"target\".report AS SELECT id FROM source.events", sql[0].toSql());
    }

    private static DatabaseConnection connection(String url, String productName, String catalog) {
        return (DatabaseConnection) Proxy.newProxyInstance(
                TestAthenaDatabase.class.getClassLoader(),
                new Class<?>[] { DatabaseConnection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getURL" -> url;
                    case "getDatabaseProductName" -> productName;
                    case "getCatalog" -> catalog;
                    case "getAutoCommit" -> true;
                    case "isClosed" -> false;
                    case "getConnectionUserName" -> "test";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestDatabaseConnection";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
