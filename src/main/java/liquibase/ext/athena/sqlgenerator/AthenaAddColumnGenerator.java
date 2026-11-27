package liquibase.ext.athena.sqlgenerator;

import java.util.List;
import java.util.StringJoiner;

import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.core.AddColumnStatement;

/** Athena adds columns with Hive DDL: ALTER TABLE ... ADD COLUMNS (...). */
public class AthenaAddColumnGenerator extends AbstractSqlGenerator<AddColumnStatement> {
    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(AddColumnStatement statement, Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    public ValidationErrors validate(AddColumnStatement statement, Database database,
            SqlGeneratorChain<AddColumnStatement> chain) {
        ValidationErrors errors = new ValidationErrors();
        for (AddColumnStatement column : columns(statement)) {
            errors.checkRequiredField("tableName", column.getTableName());
            errors.checkRequiredField("columnName", column.getColumnName());
            errors.checkRequiredField("columnType", column.getColumnType());
            if (column.getDefaultValue() != null || column.isAutoIncrement() || Boolean.TRUE.equals(column.getComputed())
                    || column.getAddAfterColumn() != null || column.getAddBeforeColumn() != null
                    || column.getAddAtPosition() != null || !column.isNullable() || column.isUnique()) {
                errors.addError("Athena ADD COLUMNS does not support the requested column constraints or values: "
                        + column.getTableName() + "." + column.getColumnName());
            }
            if (!errors.hasErrors() && "DATE".equalsIgnoreCase(AthenaCreateTableGenerator.toHiveType(column.getColumnType()))
                    && Boolean.FALSE.equals(AthenaTableFormat.isIceberg(
                            (AthenaDatabase) database, column.getSchemaName(), column.getTableName()))) {
                errors.addError("Athena ADD COLUMNS does not support DATE for external/Hive tables: "
                        + column.getTableName() + "." + column.getColumnName());
            }
        }
        return errors;
    }

    @Override
    public Sql[] generateSql(AddColumnStatement statement, Database database,
            SqlGeneratorChain<AddColumnStatement> chain) {
        List<AddColumnStatement> columns = columns(statement);
        StringJoiner definitions = new StringJoiner(", ");
        for (AddColumnStatement column : columns) {
            String remarks = column.getRemarks();
            definitions.add(AthenaDatabase.quoteDdlIdentifier(column.getColumnName()) + " "
                    + AthenaCreateTableGenerator.toHiveType(column.getColumnType())
                    + (remarks == null || remarks.isEmpty() ? ""
                            : AthenaCreateTableGenerator.comment(remarks)));
        }
        String tableName = ((AthenaDatabase) database).escapeTableNameForDdl(
                columns.get(0).getSchemaName(), columns.get(0).getTableName());
        return new Sql[] { new UnparsedSql("ALTER TABLE " + tableName + " ADD COLUMNS (" + definitions + ")") };
    }

    /** Liquibase joins the columns added by one change into a single statement. */
    private static List<AddColumnStatement> columns(AddColumnStatement statement) {
        return statement.isMultiple() ? statement.getColumns() : List.of(statement);
    }
}
