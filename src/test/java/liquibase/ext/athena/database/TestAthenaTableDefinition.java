package liquibase.ext.athena.database;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import liquibase.CatalogAndSchema;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestAthenaTableDefinition {
    @Test
    public void readsEveryRowIncludingSourceStorageMetadata() throws Exception {
        String[] lines = { "CREATE EXTERNAL TABLE `events` (`id` bigint)",
                "STORED AS PARQUET", "LOCATION 's3://source-bucket/events/'" };
        AtomicReference<String> query = new AtomicReference<>();
        AthenaDatabase database = database(lines, query, false);

        String definition = database.getTableDefinition(new CatalogAndSchema("AwsDataCatalog", "Source Db"), "Events");

        assertEquals(String.join("\n", lines), definition);
        assertEquals("SHOW CREATE TABLE `source db`.`events`", query.get());
    }

    @Test
    public void rejectsEmptyDefinitions() {
        for (String[] lines : new String[][] { {}, { "", " " } }) {
            AthenaDatabase database = database(lines, new AtomicReference<>(), false);
            assertThrows(DatabaseException.class,
                    () -> database.getTableDefinition(new CatalogAndSchema("AwsDataCatalog", "source"), "events"));
        }
    }

    @Test
    public void propagatesPermissionFailures() {
        AthenaDatabase database = database(new String[0], new AtomicReference<>(), true);

        DatabaseException error = assertThrows(DatabaseException.class,
                () -> database.getTableDefinition(new CatalogAndSchema("AwsDataCatalog", "source"), "events"));

        assertTrue(error.getMessage().contains("Access denied"));
    }

    private static AthenaDatabase database(String[] lines, AtomicReference<String> query, boolean denied) {
        AtomicInteger row = new AtomicInteger(-1);
        ResultSetMetaData resultSetMetaData = proxy(ResultSetMetaData.class, (proxy, method, args) ->
                method.getName().equals("getColumnCount") ? 1 : defaultValue(method.getReturnType()));
        ResultSet resultSet = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> row.incrementAndGet() < lines.length;
            case "getString" -> lines[row.get()];
            case "getMetaData" -> resultSetMetaData;
            default -> defaultValue(method.getReturnType());
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> {
            if (method.getName().equals("executeQuery")) {
                if (denied) {
                    throw new SQLException("Access denied");
                }
                return resultSet;
            }
            return defaultValue(method.getReturnType());
        });
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (proxy, method, args) ->
                method.getName().equals("getSQLKeywords") ? "" : defaultValue(method.getReturnType()));
        Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                query.set((String) args[0]);
                yield statement;
            }
            case "getCatalog" -> "AwsDataCatalog";
            case "getSchema" -> "source";
            case "getMetaData" -> metadata;
            case "getAutoCommit" -> true;
            case "toString" -> "TestAthenaConnection";
            default -> defaultValue(method.getReturnType());
        });
        AthenaDatabase database = new AthenaDatabase();
        database.setConnection(new JdbcConnection(connection));
        return database;
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
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
