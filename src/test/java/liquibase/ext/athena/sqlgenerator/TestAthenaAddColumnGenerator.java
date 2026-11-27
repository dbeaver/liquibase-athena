package liquibase.ext.athena.sqlgenerator;

import java.util.List;

import liquibase.CatalogAndSchema;
import liquibase.change.AddColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.core.H2Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.AddColumnStatement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestAthenaAddColumnGenerator {
    @Test
    public void generatesHiveAddColumnsWithoutCatalogOrUnsupportedLengths() {
        String[][] types = {
            { "STRING(65535)", "STRING" }, { "string(2147483647)", "STRING" }, { "STRING", "STRING" },
            { "INT(10)", "INT" }, { "integer(10)", "INT" }, { "BIGINT(19)", "BIGINT" }, { "DOUBLE(17)", "DOUBLE" },
            { "REAL", "FLOAT" }, { "BOOLEAN(1)", "BOOLEAN" }, { "TIMESTAMP(29)", "TIMESTAMP" },
            { "BLOB(65535)", "BINARY" }, { "blob ( 65535 )", "BINARY" }, { "BLOB", "BINARY" },
            { "BINARY(65535)", "BINARY" }, { "VARCHAR", "STRING" }, { "VARCHAR(40)", "VARCHAR(40)" },
            { "DECIMAL(12,3)", "DECIMAL(12,3)" }, { "ARRAY<STRING>", "ARRAY<STRING>" }
        };
        for (String[] type : types) {
            AddColumnChange change = change("qa_extra_column", type[0]);

            assertEquals("ALTER TABLE `qa_compare_target`.`qa_employees` ADD COLUMNS (`qa_extra_column` " + type[1] + ")",
                    sql(change, new AthenaDatabase()));
            assertFalse(change.validate(new AthenaDatabase()).hasErrors());
        }
    }

    @Test
    public void quotesIdentifiersAndAddsSeveralColumnsWithComments() {
        AddColumnChange change = change("New`Column", "STRING(65535)");
        change.setSchemaName("Target-Db");
        change.setTableName("Order;Archive");
        change.getColumns().get(0).setRemarks("Owner's name");
        AddColumnConfig second = new AddColumnConfig();
        second.setName("count");
        second.setType("INT");
        change.addColumn(second);

        assertEquals("ALTER TABLE `target-db`.`order;archive` ADD COLUMNS "
                + "(`new``column` STRING COMMENT 'Owner\\'s name', `count` INT)", sql(change, new AthenaDatabase()));
    }

    @Test
    public void usesDefaultSchema() {
        AthenaDatabase database = new AthenaDatabase();
        database.setDefaultCatalogName("AwsDataCatalog");
        database.setDefaultSchemaName("Target");
        AddColumnChange change = change("extra", "INT");
        change.setSchemaName(null);

        assertEquals("ALTER TABLE `target`.`qa_employees` ADD COLUMNS (`extra` INT)", sql(change, database));
    }

    @Test
    public void rejectsConstraintsAndDefaultValuesRatherThanSilentlyDroppingThem() {
        AddColumnChange notNull = change("extra", "INT");
        notNull.getColumns().get(0).setConstraints(new ConstraintsConfig().setNullable(false));
        AddColumnChange defaultValue = change("extra", "INT");
        defaultValue.getColumns().get(0).setDefaultValueNumeric(1);

        assertTrue(notNull.validate(new AthenaDatabase()).hasErrors());
        assertTrue(defaultValue.validate(new AthenaDatabase()).hasErrors());
    }

    @Test
    public void rejectsDateAdditionsForHiveTables() {
        AthenaDatabase hive = database("CREATE EXTERNAL TABLE qa_employees (id int)");
        for (String type : List.of("DATE", "date", "DATE(10)", "date ( 10 )")) {
            AddColumnChange change = change("extra", type);
            assertTrue(type, change.validate(hive).getErrorMessages().toString().contains("DATE"));
        }
        AddColumnChange multiple = change("first", "INT");
        AddColumnConfig second = new AddColumnConfig();
        second.setName("second");
        second.setType("DATE");
        multiple.addColumn(second);
        assertTrue(multiple.validate(hive).hasErrors());
        assertFalse(change("extra", "TIMESTAMP(29)").validate(hive).hasErrors());
    }

    @Test
    public void acceptsDateForIcebergOrAnUnknownTableFormat() {
        for (AthenaDatabase database : List.of(
                database("CREATE TABLE qa_employees (id int) TBLPROPERTIES ('table_type'='ICEBERG')"),
                new AthenaDatabase(), database(null))) {
            AddColumnChange change = change("extra", "DATE(10)");
            assertFalse(change.validate(database).getErrorMessages().toString(), change.validate(database).hasErrors());
            assertTrue(sql(change, database).endsWith("ADD COLUMNS (`extra` DATE)"));
        }
    }

    @Test
    public void onlyAppliesToAthena() {
        AddColumnStatement statement = new AddColumnStatement(null, "target", "events", "extra", "INT", null);

        assertTrue(new AthenaAddColumnGenerator().supports(statement, new AthenaDatabase()));
        assertFalse(new AthenaAddColumnGenerator().supports(statement, new H2Database()));
        assertFalse(sql(change("extra", "INT"), new H2Database()).contains("ADD COLUMNS"));
    }

    private static AddColumnChange change(String name, String type) {
        AddColumnConfig column = new AddColumnConfig();
        column.setName(name);
        column.setType(type);
        AddColumnChange change = new AddColumnChange();
        change.setCatalogName("awsdatacatalog");
        change.setSchemaName("qa_compare_target");
        change.setTableName("qa_employees");
        change.addColumn(column);
        return change;
    }

    private static AthenaDatabase database(String definition) {
        DatabaseConnection connection = new JdbcConnection();
        return new AthenaDatabase() {
            @Override
            public DatabaseConnection getConnection() {
                return connection;
            }

            @Override
            public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
                if (definition == null) {
                    throw new DatabaseException("Table is not available yet");
                }
                return definition;
            }
        };
    }

    private static String sql(AddColumnChange change, Database database) {
        SqlStatement[] statements = change.generateStatements(database);
        assertEquals(1, statements.length);
        Sql[] sql = SqlGeneratorFactory.getInstance().generateSql(statements[0], database);
        assertEquals(1, sql.length);
        return sql[0].toSql();
    }
}
