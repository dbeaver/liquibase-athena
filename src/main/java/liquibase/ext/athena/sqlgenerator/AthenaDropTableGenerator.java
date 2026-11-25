package liquibase.ext.athena.sqlgenerator;

import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.core.DropTableStatement;

/** Athena drops tables with Hive DDL, which takes database.table without a catalog prefix. */
public class AthenaDropTableGenerator extends AbstractSqlGenerator<DropTableStatement> {
    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(DropTableStatement statement, Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    public ValidationErrors validate(DropTableStatement statement, Database database,
            SqlGeneratorChain<DropTableStatement> chain) {
        ValidationErrors errors = new ValidationErrors();
        errors.checkRequiredField("tableName", statement.getTableName());
        if (statement.isCascadeConstraints()) {
            errors.addError("Athena DROP TABLE does not support CASCADE CONSTRAINTS: " + statement.getTableName());
        }
        return errors;
    }

    @Override
    public Sql[] generateSql(DropTableStatement statement, Database database,
            SqlGeneratorChain<DropTableStatement> chain) {
        return new Sql[] { new UnparsedSql("DROP TABLE " + ((AthenaDatabase) database).escapeTableNameForDdl(
                statement.getSchemaName(), statement.getTableName())) };
    }
}
