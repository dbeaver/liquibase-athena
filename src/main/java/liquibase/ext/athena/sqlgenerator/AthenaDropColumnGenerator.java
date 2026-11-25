package liquibase.ext.athena.sqlgenerator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.ValidationErrors;
import liquibase.exception.Warnings;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.core.DropColumnStatement;

/**
 * Athena supports DROP COLUMN only for Iceberg tables. Validation rejects a table known to have another format;
 * a table whose format cannot be read, such as one created earlier in the same changelog, only gets a warning.
 */
public class AthenaDropColumnGenerator extends AbstractSqlGenerator<DropColumnStatement> {
    private static final String SQL_LITERAL = "'(?:[^'\\\\]|\\\\.|'')*'";
    private static final Pattern TABLE_PROPERTIES = Pattern.compile(
            "\\bTBLPROPERTIES\\s*\\(\\s*((?:" + SQL_LITERAL + "\\s*=\\s*" + SQL_LITERAL + "\\s*,\\s*)*"
                    + SQL_LITERAL + "\\s*=\\s*" + SQL_LITERAL + ")\\s*\\)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TABLE_PROPERTY = Pattern.compile("(" + SQL_LITERAL + ")\\s*=\\s*(" + SQL_LITERAL + ")");

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
        if (!errors.hasErrors() && Boolean.FALSE.equals(isIceberg((AthenaDatabase) database, statement))) {
            errors.addError("Athena does not support DROP COLUMN for external/Hive tables: "
                    + tableName(database, statement));
        }
        return errors;
    }

    @Override
    public Warnings warn(DropColumnStatement statement, Database database, SqlGeneratorChain<DropColumnStatement> chain) {
        Warnings warnings = new Warnings();
        if (columns(statement).get(0).getTableName() != null && isIceberg((AthenaDatabase) database, statement) == null) {
            warnings.addWarning("Cannot confirm that the Athena table supports DROP COLUMN: "
                    + tableName(database, statement));
        }
        return warnings;
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

    private static String tableName(Database database, DropColumnStatement statement) {
        DropColumnStatement first = columns(statement).get(0);
        return ((AthenaDatabase) database).escapeTableNameForDdl(first.getSchemaName(), first.getTableName());
    }

    /** Returns null when the format is unknown: there is no connection or the table cannot be read yet. */
    private Boolean isIceberg(AthenaDatabase database, DropColumnStatement statement) {
        DatabaseConnection connection = database.getConnection();
        if (connection == null) {
            return null;
        }
        Map<String, Boolean> known = icebergTables.computeIfAbsent(connection, key -> new ConcurrentHashMap<>());
        String tableName = tableName(database, statement);
        Boolean iceberg = known.get(tableName);
        if (iceberg == null) {
            DropColumnStatement first = columns(statement).get(0);
            try {
                // SHOW TBLPROPERTIES omits table_type, so the complete definition is read.
                iceberg = hasIcebergType(database.getTableDefinition(
                        new CatalogAndSchema(null, first.getSchemaName()), first.getTableName()));
            } catch (DatabaseException e) {
                // A failed read is not remembered: the table may appear later or the failure may be temporary.
                return null;
            }
            known.put(tableName, iceberg);
        }
        return iceberg;
    }

    private static boolean hasIcebergType(String definition) {
        Matcher properties = TABLE_PROPERTIES.matcher(definition);
        if (properties.find()) {
            Matcher property = TABLE_PROPERTY.matcher(properties.group(1));
            while (property.find()) {
                if ("'table_type'".equalsIgnoreCase(property.group(1)) && "'iceberg'".equalsIgnoreCase(property.group(2))) {
                    return true;
                }
            }
        }
        return false;
    }
}
