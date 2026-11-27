package liquibase.ext.athena.sqlgenerator;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import liquibase.CatalogAndSchema;
import liquibase.database.DatabaseConnection;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;

/** Table-format checks shared by the ADD COLUMNS and DROP COLUMN validators. */
final class AthenaTableFormat {
    private static final Pattern ICEBERG_TABLE = Pattern.compile(
            "'table_type'\\s*=\\s*'iceberg'", Pattern.CASE_INSENSITIVE);
    private static final Map<DatabaseConnection, Map<String, Boolean>> ICEBERG_TABLES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AthenaTableFormat() {
    }

    /** Returns null when there is no connection or the table cannot be read yet. */
    static Boolean isIceberg(AthenaDatabase database, String schemaName, String tableName) {
        DatabaseConnection connection = database.getConnection();
        if (connection == null) {
            return null;
        }
        String qualifiedName = database.escapeTableNameForDdl(schemaName, tableName);
        Map<String, Boolean> known = ICEBERG_TABLES.computeIfAbsent(connection, key -> new ConcurrentHashMap<>());
        return known.computeIfAbsent(qualifiedName, key -> {
            try {
                // SHOW TBLPROPERTIES omits table_type, so read the complete definition once per connection.
                return ICEBERG_TABLE.matcher(database.getTableDefinition(new CatalogAndSchema(null, schemaName), tableName)).find();
            } catch (DatabaseException e) {
                // Do not cache failed reads: the table may appear later or the failure may be temporary.
                return null;
            }
        });
    }
}
