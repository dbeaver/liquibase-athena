package liquibase.ext.athena.sqlgenerator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.ValidationErrors;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.core.DropColumnStatement;

/**
 * Athena supports DROP COLUMN only for Iceberg tables. Validation rejects a table known to have another format;
 * a table which cannot be read, such as one created earlier in the same changelog, is not rejected.
 */
public class AthenaDropColumnGenerator extends AbstractSqlGenerator<DropColumnStatement> {
    private static final Pattern ICEBERG_TABLE = Pattern.compile(
            "'table_type'\\s*=\\s*'iceberg'", Pattern.CASE_INSENSITIVE);

    // Several columns of one table are dropped by separate statements; a known format is read once per connection.
    private final Map<DatabaseConnection, Map<String, Boolean>> icebergTables =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(DropColumnStatement statement, Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    public ValidationErrors validate(DropColumnStatement statement, Database database,
            SqlGeneratorChain<DropColumnStatement> chain) {
        ValidationErrors errors = new ValidationErrors();
        for (DropColumnStatement column : columns(statement)) {
            errors.checkRequiredField("tableName", column.getTableName());
            errors.checkRequiredField("columnName", column.getColumnName());
        }
        DropColumnStatement first = columns(statement).get(0);
        if (!errors.hasErrors() && Boolean.FALSE.equals(isIceberg((AthenaDatabase) database, first))) {
            errors.addError("Athena does not support DROP COLUMN for external/Hive tables: "
                    + ((AthenaDatabase) database).escapeTableNameForDdl(first.getSchemaName(), first.getTableName()));
        }
        return errors;
    }

    @Override
    public Sql[] generateSql(DropColumnStatement statement, Database database,
            SqlGeneratorChain<DropColumnStatement> chain) {
        // Athena drops one column per statement.
        return columns(statement).stream().map(column -> new UnparsedSql("ALTER TABLE "
                + ((AthenaDatabase) database).escapeTableNameForDdl(column.getSchemaName(), column.getTableName())
                + " DROP COLUMN " + AthenaDatabase.quoteDdlIdentifier(column.getColumnName()))).toArray(Sql[]::new);
    }

    /** Liquibase joins the columns dropped by one change into a single statement. */
    private static List<DropColumnStatement> columns(DropColumnStatement statement) {
        return statement.isMultiple() ? statement.getColumns() : List.of(statement);
    }

    /** Returns null when the format is unknown: there is no connection or the table cannot be read yet. */
    private Boolean isIceberg(AthenaDatabase database, DropColumnStatement column) {
        DatabaseConnection connection = database.getConnection();
        if (connection == null) {
            return null;
        }
        String tableName = database.escapeTableNameForDdl(column.getSchemaName(), column.getTableName());
        Map<String, Boolean> known = icebergTables.computeIfAbsent(connection, key -> new ConcurrentHashMap<>());
        return known.computeIfAbsent(tableName, key -> {
            try {
                // SHOW TBLPROPERTIES omits table_type, so the complete definition is read.
                return ICEBERG_TABLE.matcher(database.getTableDefinition(
                        new CatalogAndSchema(null, column.getSchemaName()), column.getTableName())).find();
            } catch (DatabaseException e) {
                // A failed read is not remembered: the table may appear later or the failure may be temporary.
                return null;
            }
        });
    }
}
