package liquibase.ext.athena.sqlgenerator;

import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.core.ModifyDataTypeStatement;

/**
 * The default generator accepts modifyDataType for any database and emits generic SQL, which is not valid
 * Athena DDL. Validation reports the change as unsupported; the generated SQL is left to the default generator.
 */
public class AthenaModifyDataTypeGenerator extends AbstractSqlGenerator<ModifyDataTypeStatement> {
    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(ModifyDataTypeStatement statement, Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    public ValidationErrors validate(ModifyDataTypeStatement statement, Database database,
            SqlGeneratorChain<ModifyDataTypeStatement> chain) {
        return new ValidationErrors().addError("Athena does not support modifyDataType");
    }

    @Override
    public Sql[] generateSql(ModifyDataTypeStatement statement, Database database,
            SqlGeneratorChain<ModifyDataTypeStatement> chain) {
        return chain.generateSql(statement, database);
    }
}
