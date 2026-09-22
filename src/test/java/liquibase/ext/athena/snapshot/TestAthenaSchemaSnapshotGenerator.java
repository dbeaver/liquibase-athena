package liquibase.ext.athena.snapshot;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicReference;

import liquibase.database.jvm.JdbcConnection;
import liquibase.ext.athena.database.AthenaDatabase;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TestAthenaSchemaSnapshotGenerator {
    @Test
    public void scopesSchemaDiscoveryToCurrentCatalog() throws Exception {
        AtomicReference<String> requestedCatalog = new AtomicReference<>();
        AtomicReference<String> requestedSchemaPattern = new AtomicReference<>();
        ResultSet resultSet = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> false;
            case "isClosed" -> false;
            case "toString" -> "EmptySchemaResultSet";
            default -> defaultValue(method.getReturnType());
        });
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if (method.getName().equals("getSchemas") && args != null && args.length == 2) {
                requestedCatalog.set((String) args[0]);
                requestedSchemaPattern.set((String) args[1]);
                return resultSet;
            }
            if (method.getName().equals("getSQLKeywords")) {
                return "";
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
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

        String[] schemas = new TestableAthenaSchemaSnapshotGenerator().getSchemaNames(database);

        assertArrayEquals(new String[0], schemas);
        assertEquals("AwsDataCatalog", requestedCatalog.get());
        assertNull(requestedSchemaPattern.get());
    }

    @SuppressWarnings("unchecked")
    private static class TestableAthenaSchemaSnapshotGenerator extends AthenaSchemaSnapshotGenerator {
        private String[] getSchemaNames(AthenaDatabase database) throws Exception {
            return getDatabaseSchemaNames(database);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
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
