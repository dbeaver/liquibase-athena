package liquibase.ext.athena.sqlgenerator;

import liquibase.database.Database;
import liquibase.exception.ValidationErrors;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.AbstractSqlGenerator;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddAutoIncrementStatement;
import liquibase.statement.core.AddDefaultValueStatement;
import liquibase.statement.core.AddForeignKeyConstraintStatement;
import liquibase.statement.core.AddPrimaryKeyStatement;
import liquibase.statement.core.AddUniqueConstraintStatement;
import liquibase.statement.core.CreateIndexStatement;
import liquibase.statement.core.DropDefaultValueStatement;
import liquibase.statement.core.DropForeignKeyConstraintStatement;
import liquibase.statement.core.DropIndexStatement;
import liquibase.statement.core.DropPrimaryKeyStatement;
import liquibase.statement.core.DropUniqueConstraintStatement;
import liquibase.statement.core.ModifyDataTypeStatement;
import liquibase.statement.core.SetNullableStatement;

/**
 * The default generators accept these statements for any database and emit generic SQL, which is not valid
 * Athena DDL. Validation reports them as unsupported; the generated SQL is left to the default generator.
 */
public abstract class AthenaUnsupportedGenerator<T extends SqlStatement> extends AbstractSqlGenerator<T> {
    @Override
    public int getPriority() {
        return PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(T statement, Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    public ValidationErrors validate(T statement, Database database, SqlGeneratorChain<T> chain) {
        String name = statement.getClass().getSimpleName().replace("Statement", "");
        return new ValidationErrors().addError(
                "Athena does not support " + Character.toLowerCase(name.charAt(0)) + name.substring(1));
    }

    @Override
    public Sql[] generateSql(T statement, Database database, SqlGeneratorChain<T> chain) {
        return chain.generateSql(statement, database);
    }

    // Liquibase picks generators by their statement type and skips the ones declared for any statement.

    public static class ModifyDataType extends AthenaUnsupportedGenerator<ModifyDataTypeStatement> {
    }

    public static class SetNullable extends AthenaUnsupportedGenerator<SetNullableStatement> {
    }

    public static class AddDefaultValue extends AthenaUnsupportedGenerator<AddDefaultValueStatement> {
    }

    public static class DropDefaultValue extends AthenaUnsupportedGenerator<DropDefaultValueStatement> {
    }

    public static class AddAutoIncrement extends AthenaUnsupportedGenerator<AddAutoIncrementStatement> {
    }

    public static class AddPrimaryKey extends AthenaUnsupportedGenerator<AddPrimaryKeyStatement> {
    }

    public static class DropPrimaryKey extends AthenaUnsupportedGenerator<DropPrimaryKeyStatement> {
    }

    public static class AddForeignKey extends AthenaUnsupportedGenerator<AddForeignKeyConstraintStatement> {
    }

    public static class DropForeignKey extends AthenaUnsupportedGenerator<DropForeignKeyConstraintStatement> {
    }

    public static class AddUniqueConstraint extends AthenaUnsupportedGenerator<AddUniqueConstraintStatement> {
    }

    public static class DropUniqueConstraint extends AthenaUnsupportedGenerator<DropUniqueConstraintStatement> {
    }

    public static class CreateIndex extends AthenaUnsupportedGenerator<CreateIndexStatement> {
    }

    public static class DropIndex extends AthenaUnsupportedGenerator<DropIndexStatement> {
    }
}
