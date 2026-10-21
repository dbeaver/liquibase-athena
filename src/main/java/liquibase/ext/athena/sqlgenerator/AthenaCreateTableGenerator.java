package liquibase.ext.athena.sqlgenerator;

import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.exception.Warnings;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.CreateTableGenerator;
import liquibase.statement.core.CreateTableStatement;

/**
 * Athena CREATE TABLE uses Hive DDL, unlike its SELECT and CREATE VIEW statements.
 * Storage clauses are not represented by Liquibase's generic CreateTableStatement.
 */
public class AthenaCreateTableGenerator extends CreateTableGenerator {
    private static final Pattern NUMERIC_DISPLAY_WIDTH = Pattern.compile(
            "(?i)^(TINYINT|SMALLINT|INT|INTEGER|BIGINT|FLOAT|REAL|DOUBLE)\\s*\\(\\s*\\d+\\s*\\)$");

    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(CreateTableStatement statement, Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public ValidationErrors validate(CreateTableStatement statement, Database database, SqlGeneratorChain chain) {
        ValidationErrors errors = super.validate(statement, database, chain);
        if (statement.getTableType() != null && !statement.getTableType().isBlank()
                && !"EXTERNAL".equalsIgnoreCase(statement.getTableType().trim())) {
            errors.addError("Athena generic table creation only supports EXTERNAL tables");
        }
        if (statement.getPrimaryKeyConstraint() != null || !statement.getForeignKeyConstraints().isEmpty()
                || !statement.getUniqueConstraints().isEmpty() || !statement.getNotNullColumns().isEmpty()) {
            errors.addError("Athena external tables do not support column constraints");
        }
        if (!statement.getAutoIncrementConstraints().isEmpty() || !statement.getDefaultValues().isEmpty()) {
            errors.addError("Athena external tables do not support auto-increment or default values");
        }
        for (String column : statement.getColumns()) {
            errors.checkRequiredField("type for column " + column, statement.getColumnTypes().get(column));
            if (statement.isComputed(column)) {
                errors.addError("Athena external tables do not support computed columns");
            }
        }
        return errors;
    }

    @Override
    public Warnings warn(CreateTableStatement statement, Database database, SqlGeneratorChain<CreateTableStatement> chain) {
        return super.warn(statement, database, chain).addWarning(
                "Athena table storage metadata is not included: add LOCATION and any required storage and partition clauses");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Sql[] generateSql(CreateTableStatement statement, Database database, SqlGeneratorChain chain) {
        String schema = statement.getSchemaName();
        if (schema == null) {
            schema = database.getDefaultSchemaName();
        }
        // Athena table DDL accepts database.table, not catalog.database.table.
        String tableName = (schema == null || schema.isBlank() ? "" : quoteIdentifier(schema) + ".")
                + quoteIdentifier(statement.getTableName());
        StringJoiner columns = new StringJoiner(", ");
        for (String column : statement.getColumns()) {
            String type = statement.getColumnTypes().get(column).toDatabaseDataType(database).toString();
            type = NUMERIC_DISPLAY_WIDTH.matcher(type).replaceFirst("$1");
            if ("INTEGER".equalsIgnoreCase(type)) {
                type = "INT";
            } else if ("REAL".equalsIgnoreCase(type)) {
                type = "FLOAT";
            } else if ("VARCHAR".equalsIgnoreCase(type) || "TEXT".equalsIgnoreCase(type)) {
                type = "STRING";
            }
            columns.add(quoteIdentifier(column) + " " + type + comment(statement.getColumnRemarks(column), database));
        }
        String sql = "CREATE EXTERNAL TABLE " + (statement.isIfNotExists() ? "IF NOT EXISTS " : "")
                + tableName + " (" + columns + ")" + comment(statement.getRemarks(), database);
        return new Sql[] { new UnparsedSql(sql, getAffectedTable(statement)) };
    }

    private static String quoteIdentifier(String name) {
        return "`" + name.trim().toLowerCase(Locale.ROOT).replace("`", "``") + "`";
    }

    private static String comment(String remarks, Database database) {
        return remarks == null ? "" : " COMMENT '" + database.escapeStringForDatabase(remarks) + "'";
    }
}
