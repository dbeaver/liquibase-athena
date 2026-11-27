package liquibase.ext.athena.sqlgenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import liquibase.CatalogAndSchema;
import liquibase.change.ColumnConfig;
import liquibase.change.core.DropColumnChange;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.core.H2Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.DropColumnStatement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestAthenaDropColumnGenerator {
    private static final String ICEBERG = "CREATE TABLE qa_employees (id int)\n"
            + "TBLPROPERTIES ('table_type'='ICEBERG', 'write_compression'='zstd');";

    @Test
    public void dropsIcebergColumnsAndReadsTheTableFormatOncePerConnection() {
        AtomicInteger reads = new AtomicInteger();
        AthenaDatabase database = database(ICEBERG, reads);
        DropColumnChange first = change("extra");
        DropColumnChange second = change("another");

        assertFalse(first.validate(database).hasErrors());
        assertFalse(second.validate(database).hasErrors());
        assertEquals(1, reads.get());
        assertEquals(List.of("ALTER TABLE `qa_compare_target`.`qa_employees` DROP COLUMN `extra`"), sql(first, database));
    }

    @Test
    public void rejectsColumnDropsForHiveTables() {
        String hive = "CREATE EXTERNAL TABLE qa_employees (id int) LOCATION 's3://bucket/employees/'\n"
                + "TBLPROPERTIES ('table_type'='EXTERNAL')";
        String propertyInsideValue = "CREATE EXTERNAL TABLE qa_employees (id int) "
                + "TBLPROPERTIES ('comment'='\\'table_type\\'=\\'iceberg\\'')";
        for (String definition : new String[] { hive, propertyInsideValue, "CREATE EXTERNAL TABLE qa_employees (id int)" }) {
            assertTrue(change("extra").validate(database(definition, new AtomicInteger())).getErrorMessages().toString()
                    .contains("external/Hive"));
        }
    }

    @Test
    public void doesNotRejectATableWhichCannotBeRead() {
        // Liquibase validates a whole changelog first, so the table may be created by an earlier change set.
        for (AthenaDatabase database : new AthenaDatabase[] { new AthenaDatabase(), database(null, new AtomicInteger()) }) {
            DropColumnChange change = change("extra");

            assertFalse(change.validate(database).getErrorMessages().toString(), change.validate(database).hasErrors());
        }
    }

    @Test
    public void readsTheTableFormatAgainAfterAFailedRead() {
        AtomicInteger reads = new AtomicInteger();
        String[] definition = { null };
        DatabaseConnection connection = new JdbcConnection();
        AthenaDatabase database = new AthenaDatabase() {
            @Override
            public DatabaseConnection getConnection() {
                return connection;
            }

            @Override
            public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
                reads.incrementAndGet();
                if (definition[0] == null) {
                    throw new DatabaseException("Rate exceeded");
                }
                return definition[0];
            }
        };

        assertFalse(change("extra").validate(database).hasErrors());
        definition[0] = "CREATE EXTERNAL TABLE qa_employees (id int) LOCATION 's3://bucket/employees/'";
        assertTrue(change("extra").validate(database).getErrorMessages().toString().contains("external/Hive"));
        assertEquals(2, reads.get());
    }

    @Test
    public void dropsSeveralColumnsBySeparateStatementsWithoutSplittingQuotedNames() {
        DropColumnChange change = change(null);
        change.setTableName("table;name");
        change.addColumn(new ColumnConfig().setName("first;column"));
        change.addColumn(new ColumnConfig().setName("Second"));
        AthenaDatabase database = database(ICEBERG, new AtomicInteger());

        assertFalse(change.validate(database).hasErrors());
        assertEquals(List.of("ALTER TABLE `qa_compare_target`.`table;name` DROP COLUMN `first;column`",
                "ALTER TABLE `qa_compare_target`.`table;name` DROP COLUMN `second`"), sql(change, database));
    }

    @Test
    public void onlyAppliesToAthena() {
        DropColumnStatement statement = new DropColumnStatement(null, "target", "events", "extra");

        assertTrue(new AthenaDropColumnGenerator().supports(statement, new AthenaDatabase()));
        assertFalse(new AthenaDropColumnGenerator().supports(statement, new H2Database()));
    }

    private static DropColumnChange change(String column) {
        DropColumnChange change = new DropColumnChange();
        change.setCatalogName("awsdatacatalog");
        change.setSchemaName("qa_compare_target");
        change.setTableName("qa_employees");
        change.setColumnName(column);
        return change;
    }

    /** A connected database whose SHOW CREATE TABLE returns the definition, or fails when it is null. */
    private static AthenaDatabase database(String definition, AtomicInteger reads) {
        DatabaseConnection connection = new JdbcConnection();
        return new AthenaDatabase() {
            @Override
            public DatabaseConnection getConnection() {
                return connection;
            }

            @Override
            public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
                reads.incrementAndGet();
                if (definition == null) {
                    throw new DatabaseException("Access denied");
                }
                return definition;
            }
        };
    }

    private static List<String> sql(DropColumnChange change, Database database) {
        List<String> result = new ArrayList<>();
        for (SqlStatement statement : change.generateStatements(database)) {
            for (Sql sql : SqlGeneratorFactory.getInstance().generateSql(statement, database)) {
                result.add(sql.toSql());
            }
        }
        return result;
    }
}
