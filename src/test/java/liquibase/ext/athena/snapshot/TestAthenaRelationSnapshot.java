package liquibase.ext.athena.snapshot;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import liquibase.CatalogAndSchema;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffGeneratorFactory;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.ext.athena.change.CreateAthenaTableChange;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.EmptyDatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.structure.core.Catalog;
import liquibase.structure.core.Column;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import liquibase.structure.core.View;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestAthenaRelationSnapshot {
    @Test
    public void discoversTablesAndViewsInASchemaWithHiddenCatalog() throws Exception {
        assertRelationsDiscovered("AwsDataCatalog");
    }

    @Test
    public void discoversRelationsWhenMetadataUsesLowercaseCatalog() throws Exception {
        assertRelationsDiscovered("awsdatacatalog");
    }

    @Test
    public void discoversSimbaExternalTablesAndVirtualViews() throws Exception {
        assertRelationsDiscovered("AwsDataCatalog", "EXTERNAL_TABLE", "VIRTUAL_VIEW");
    }

    @Test
    public void discoversSimbaManagedTables() throws Exception {
        assertRelationsDiscovered("AwsDataCatalog", "MANAGED_TABLE", "VIEW");
    }

    @Test
    public void discoversSimbaExternalType() throws Exception {
        assertRelationsDiscovered("AwsDataCatalog", "EXTERNAL", "VIEW");
    }

    @Test
    public void treatsSchemaPatternCharactersLiterally() throws Exception {
        assertRelationsDiscovered("AwsDataCatalog", "EXTERNAL_TABLE", "VIRTUAL_VIEW", "db_test%data");
    }

    @Test
    public void generatesChangesFromDiscoveredRelationsDespiteCatalogCaseDifference() throws Exception {
        assertGeneratedChanges(assertRelationsDiscovered("awsdatacatalog"));
    }

    @Test
    public void generatesNativeChangesFromLegacySimbaRelations() throws Exception {
        assertGeneratedChanges(assertRelationsDiscovered("AwsDataCatalog", "EXTERNAL_TABLE", "VIRTUAL_VIEW"));
    }

    private static void assertGeneratedChanges(DatabaseSnapshot source) throws Exception {
        AthenaDatabase targetDatabase = new AthenaDatabase();
        targetDatabase.setDefaultCatalogName("AwsDataCatalog");
        targetDatabase.setDefaultSchemaName("sampledb");
        var comparisons = new CompareControl.SchemaComparison[] { new CompareControl.SchemaComparison(
                new CatalogAndSchema("AwsDataCatalog", "dbeaver"), new CatalogAndSchema("AwsDataCatalog", "sampledb")) };
        var control = new CompareControl(comparisons, "Table,View,Column");
        var diff = DiffGeneratorFactory.getInstance().compare(source, new EmptyDatabaseSnapshot(targetDatabase), control);
        assertEquals(1, diff.getMissingObjects(Table.class).size());
        assertEquals(1, diff.getMissingObjects(View.class).size());

        var changes = new DiffToChangeLog(diff, new DiffOutputControl(false, true, false, comparisons)).generateChangeSets();
        assertEquals(2, changes.size());
        CreateAthenaTableChange table = changes.stream().flatMap(changeSet -> changeSet.getChanges().stream())
                .filter(CreateAthenaTableChange.class::isInstance).map(CreateAthenaTableChange.class::cast)
                .findFirst().orElseThrow();
        assertEquals(1, table.getColumns().size());
        table.setSchemaName("sampledb");
        assertEquals("CREATE EXTERNAL TABLE `sampledb`.`events` (`id` bigint) LOCATION 's3://bucket/events/'",
                SqlGeneratorFactory.getInstance().generateSql(table.generateStatements(targetDatabase)[0], targetDatabase)[0].toSql());
    }

    private static DatabaseSnapshot assertRelationsDiscovered(String metadataCatalog) throws Exception {
        return assertRelationsDiscovered(metadataCatalog, "TABLE", "VIEW");
    }

    private static DatabaseSnapshot assertRelationsDiscovered(String metadataCatalog, String tableType, String viewType) throws Exception {
        return assertRelationsDiscovered(metadataCatalog, tableType, viewType, "dbeaver");
    }

    private static DatabaseSnapshot assertRelationsDiscovered(String metadataCatalog, String tableType, String viewType,
            String sourceSchema) throws Exception {
        List<String> calls = new ArrayList<>();
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getSQLKeywords" -> "";
            case "getSearchStringEscape" -> "\\";
            case "getSchemas" -> rows(List.of("TABLE_CATALOG", "TABLE_SCHEM"), List.of(
                    Map.of("TABLE_CATALOG", "AwsDataCatalog", "TABLE_SCHEM", sourceSchema),
                    Map.of("TABLE_CATALOG", "AwsDataCatalog", "TABLE_SCHEM", "sampledb")));
            case "getCatalogs" -> rows(List.of("TABLE_CAT"), List.of(Map.of("TABLE_CAT", "AwsDataCatalog")));
            case "getTables" -> {
                calls.add("getTables " + Arrays.deepToString(args));
                List<Map<String, Object>> found = new ArrayList<>();
                String schemaPattern = sourceSchema.replace("_", "\\_").replace("%", "\\%");
                if ("AwsDataCatalog".equals(args[0]) && schemaPattern.equals(args[1])) {
                    if (args[3] == null || Arrays.asList((String[]) args[3]).contains(tableType)) {
                        found.add(Map.of("TABLE_CAT", metadataCatalog, "TABLE_SCHEM", sourceSchema,
                                "TABLE_NAME", "events", "TABLE_TYPE", tableType));
                    }
                    if (args[3] == null || Arrays.asList((String[]) args[3]).contains(viewType)) {
                        found.add(Map.of("TABLE_CAT", metadataCatalog, "TABLE_SCHEM", sourceSchema,
                                "TABLE_NAME", "report", "TABLE_TYPE", viewType));
                    }
                    if (args[3] == null) {
                        found.add(Map.of("TABLE_CAT", metadataCatalog, "TABLE_SCHEM", sourceSchema,
                                "TABLE_NAME", "unsupported", "TABLE_TYPE", "SYSTEM TABLE"));
                    }
                }
                yield rows(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"), found);
            }
            case "getColumns" -> {
                List<Map<String, Object>> found = new ArrayList<>();
                for (String table : List.of("events", "report")) {
                    if ("%".equals(args[2]) || table.equals(args[2])) {
                        found.add(Map.ofEntries(
                                Map.entry("TABLE_CAT", metadataCatalog), Map.entry("TABLE_SCHEM", sourceSchema),
                                Map.entry("TABLE_NAME", table), Map.entry("COLUMN_NAME", "id"),
                                Map.entry("DATA_TYPE", Types.BIGINT), Map.entry("TYPE_NAME", "bigint"),
                                Map.entry("COLUMN_SIZE", 19), Map.entry("DECIMAL_DIGITS", 0),
                                Map.entry("NUM_PREC_RADIX", 10), Map.entry("NULLABLE", DatabaseMetaData.columnNullable),
                                Map.entry("ORDINAL_POSITION", 1), Map.entry("IS_AUTOINCREMENT", "NO")));
                    }
                }
                yield rows(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                        "COLUMN_SIZE", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "ORDINAL_POSITION", "IS_AUTOINCREMENT"), found);
            }
            default -> defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "getCatalog" -> "AwsDataCatalog";
            case "getSchema" -> "sampledb";
            case "getMetaData" -> metadata;
            case "prepareStatement" -> proxy(PreparedStatement.class, (statement, queryMethod, queryArgs) ->
                    queryMethod.getName().equals("executeQuery")
                            ? rows(List.of("ddl"), List.of(Map.of("ddl", ((String) args[0]).startsWith("SHOW CREATE TABLE ")
                                    ? "CREATE EXTERNAL TABLE dbeaver.events (`id` bigint) LOCATION 's3://bucket/events/'"
                                    : "CREATE VIEW dbeaver.report AS SELECT id FROM dbeaver.events")))
                            : defaultValue(queryMethod.getReturnType()));
            case "getAutoCommit" -> true;
            case "toString" -> "TestAthenaRelationConnection";
            default -> defaultValue(method.getReturnType());
        });
        AthenaDatabase database = new AthenaDatabase();
        database.setConnection(new JdbcConnection(connection));
        database.setObjectQuotingStrategy(ObjectQuotingStrategy.QUOTE_ALL_OBJECTS);
        var snapshot = SnapshotGeneratorFactory.getInstance().createSnapshot(
                new CatalogAndSchema(null, sourceSchema), database,
                new SnapshotControl(database, Catalog.class, Schema.class, Table.class, View.class, Column.class));

        assertEquals(1, snapshot.get(Schema.class).size());
        assertEquals(calls.toString(), 1, snapshot.get(Table.class).size());
        assertEquals(calls.toString(), 1, snapshot.get(View.class).size());
        assertEquals(2, snapshot.get(Column.class).size());
        assertEquals("events", snapshot.get(Table.class).iterator().next().getName());
        assertEquals("report", snapshot.get(View.class).iterator().next().getName());
        assertEquals("Table and view generators must share one unfiltered JDBC lookup", 1, calls.size());
        assertTrue(calls.get(0).endsWith(", %, null]"));
        return snapshot;
    }

    private static ResultSet rows(List<String> columns, List<Map<String, Object>> data) {
        AtomicInteger row = new AtomicInteger(-1);
        AtomicReference<Object> value = new AtomicReference<>();
        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columns.size();
            case "getColumnName", "getColumnLabel" -> columns.get((int) args[0] - 1);
            case "getColumnType" -> Types.VARCHAR;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> row.incrementAndGet() < data.size();
            case "getMetaData" -> metadata;
            case "findColumn" -> columns.indexOf(args[0]) + 1;
            case "getObject", "getString" -> {
                String name = args[0] instanceof Integer index ? columns.get(index - 1) : (String) args[0];
                Object result = data.get(row.get()).get(name);
                value.set(result);
                yield result;
            }
            case "wasNull" -> value.get() == null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
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
