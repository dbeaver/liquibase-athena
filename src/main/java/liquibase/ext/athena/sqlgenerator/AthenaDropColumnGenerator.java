package liquibase.ext.athena.sqlgenerator;

import java.util.List;

import liquibase.database.Database;
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
        if (!errors.hasErrors() && Boolean.FALSE.equals(AthenaTableFormat.isIceberg(
                (AthenaDatabase) database, first.getSchemaName(), first.getTableName()))) {
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

}
