package liquibase.ext.athena.sqlgenerator;

import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.CreateTableChange;
import liquibase.database.Database;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.database.core.H2Database;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.CreateTableStatement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestAthenaCreateTableGenerator {
    @Test
    public void generatesHiveDdlForReportedEmployeeTable() {
        AthenaDatabase database = new AthenaDatabase();
        database.setObjectQuotingStrategy(ObjectQuotingStrategy.QUOTE_ALL_OBJECTS);
        CreateTableChange change = new CreateTableChange();
        change.setCatalogName("awsdatacatalog");
        change.setSchemaName("qa_compare_target");
        change.setTableName("qa_employees");
        addColumn(change, "id", "BIGINT(8)");
        addColumn(change, "employee_name", "VARCHAR(5)");
        addColumn(change, "department_id", "INT(4)");
        addColumn(change, "salary", "DOUBLE(8)");

        assertEquals("CREATE EXTERNAL TABLE `qa_compare_target`.`qa_employees` "
                + "(`id` BIGINT, `employee_name` VARCHAR(5), `department_id` INT, `salary` DOUBLE)",
                generateSql(change, database));
    }

    @Test
    public void preservesDecimalPrecisionAndStringLength() {
        CreateTableChange change = new CreateTableChange();
        change.setTableName("order");
        addColumn(change, "amount", "DECIMAL(12,2)");
        addColumn(change, "name", "VARCHAR(50)");
        addColumn(change, "code", "CHAR(3)");

        assertEquals("CREATE EXTERNAL TABLE `order` (`amount` DECIMAL(12, 2), `name` VARCHAR(50), `code` CHAR(3))",
                generateSql(change, new AthenaDatabase()));
    }

    @Test
    public void usesDefaultSchemaAndSupportsIfNotExists() {
        AthenaDatabase database = new AthenaDatabase();
        database.setDefaultCatalogName("AwsDataCatalog");
        database.setDefaultSchemaName("Target");
        CreateTableChange change = new CreateTableChange();
        change.setTableName("Events");
        change.setIfNotExists(true);
        addColumn(change, "select", "BIGINT");

        assertEquals("CREATE EXTERNAL TABLE IF NOT EXISTS `target`.`events` (`select` BIGINT)",
                generateSql(change, database));
    }

    @Test
    public void preservesCommentsWithoutRewritingTheirContents() {
        CreateTableChange change = new CreateTableChange();
        change.setTableName("events");
        change.setRemarks("Owner's BIGINT(8)");
        ColumnConfig column = addColumn(change, "id", "BIGINT(8)");
        column.setRemarks("A \"quoted\" name");

        assertEquals("CREATE EXTERNAL TABLE `events` (`id` BIGINT COMMENT 'A \"quoted\" name') "
                + "COMMENT 'Owner''s BIGINT(8)'", generateSql(change, new AthenaDatabase()));
    }

    @Test
    public void rejectsUnsupportedConstraintsRatherThanSilentlyDroppingThem() {
        AthenaDatabase database = new AthenaDatabase();
        CreateTableChange change = new CreateTableChange();
        change.setTableName("events");
        addColumn(change, "id", "BIGINT").setConstraints(new ConstraintsConfig().setNullable(false));
        CreateTableStatement statement = (CreateTableStatement) change.generateStatements(database)[0];

        assertTrue(new AthenaCreateTableGenerator().validate(statement, database, null).hasErrors());
    }

    @Test
    public void onlyAppliesToAthenaAndWarnsAboutMissingStorageMetadata() {
        AthenaCreateTableGenerator generator = new AthenaCreateTableGenerator();
        CreateTableStatement statement = new CreateTableStatement(null, null, "events");

        assertTrue(generator.supports(statement, new AthenaDatabase()));
        assertFalse(generator.supports(statement, new H2Database()));
        assertTrue(SqlGeneratorFactory.getInstance().warn(statement, new AthenaDatabase()).getMessages().stream()
                .anyMatch(message -> message.contains("LOCATION")));
    }

    @Test
    public void mapsHiveNumericTypesAndUnboundedStrings() {
        CreateTableChange change = new CreateTableChange();
        change.setTableName("events");
        addColumn(change, "count", "INTEGER(4)");
        addColumn(change, "ratio", "REAL(4)");
        addColumn(change, "description", "VARCHAR");

        assertEquals("CREATE EXTERNAL TABLE `events` (`count` INT, `ratio` FLOAT, `description` STRING)",
                generateSql(change, new AthenaDatabase()));
    }

    @Test
    public void rejectsDefaultValuesAndUnsupportedTableTypes() {
        AthenaDatabase database = new AthenaDatabase();
        CreateTableChange change = new CreateTableChange();
        change.setTableName("events");
        change.setTableType("TEMPORARY");
        addColumn(change, "id", "BIGINT").setDefaultValueNumeric(1);
        CreateTableStatement statement = (CreateTableStatement) change.generateStatements(database)[0];

        assertEquals(2, SqlGeneratorFactory.getInstance().validate(statement, database).getErrorMessages().size());
    }

    private static ColumnConfig addColumn(CreateTableChange change, String name, String type) {
        ColumnConfig column = new ColumnConfig().setName(name).setType(type);
        change.addColumn(column);
        return column;
    }

    private static String generateSql(CreateTableChange change, Database database) {
        Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(change.generateStatements(database)[0], database);
        assertEquals(1, sql.length);
        return sql[0].toSql();
    }
}
