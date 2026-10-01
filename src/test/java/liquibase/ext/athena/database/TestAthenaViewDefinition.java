package liquibase.ext.athena.database;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import liquibase.CatalogAndSchema;
import liquibase.database.jvm.JdbcConnection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestAthenaViewDefinition {
    @Test
    public void readsAllRowsReturnedByShowCreateView() throws Exception {
        String[] lines = {"CREATE VIEW analytics.report AS", "SELECT id", "FROM analytics.events"};
        AtomicInteger row = new AtomicInteger(-1);
        AtomicReference<String> query = new AtomicReference<>();
        ResultSetMetaData resultSetMetaData = proxy(ResultSetMetaData.class, (proxy, method, args) ->
                method.getName().equals("getColumnCount") ? 1 : defaultValue(method.getReturnType()));
        ResultSet resultSet = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> row.incrementAndGet() < lines.length;
            case "getString" -> lines[row.get()];
            case "getMetaData" -> resultSetMetaData;
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> resultSet;
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        });
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (proxy, method, args) ->
                method.getName().equals("getSQLKeywords") ? "" : defaultValue(method.getReturnType()));
        Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                query.set((String) args[0]);
                yield statement;
            }
            case "getCatalog" -> "AwsDataCatalog";
            case "getSchema" -> "analytics";
            case "getMetaData" -> metadata;
            case "getAutoCommit" -> true;
            case "isClosed" -> false;
            case "toString" -> "TestAthenaConnection";
            default -> defaultValue(method.getReturnType());
        });
        AthenaDatabase database = new AthenaDatabase();
        database.setConnection(new JdbcConnection(connection));

        String definition = database.getViewDefinition(new CatalogAndSchema("AwsDataCatalog", "analytics"), "report");

        assertEquals("FULL_DEFINITION: CREATE VIEW analytics.report AS\nSELECT id\nFROM analytics.events", definition);
        assertTrue(query.get().startsWith("SHOW CREATE VIEW "));
        assertTrue(query.get().contains("report"));
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
