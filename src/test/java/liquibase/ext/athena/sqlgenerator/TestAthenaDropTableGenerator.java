package liquibase.ext.athena.sqlgenerator;

import liquibase.change.core.DropTableChange;
import liquibase.database.core.H2Database;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.core.DropTableStatement;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestAthenaDropTableGenerator {
    @Test
    public void dropsTableWithoutCatalogAndUsesDefaultSchema() {
        AthenaDatabase database = new AthenaDatabase();
        database.setDefaultCatalogName("awsdatacatalog");
        database.setDefaultSchemaName("qa_compare_target");
        DropTableChange change = new DropTableChange();
        change.setCatalogName("awsdatacatalog");
        change.setTableName("QA_Target_Only");

        assertEquals("DROP TABLE `qa_compare_target`.`qa_target_only`",
                SqlGeneratorFactory.getInstance().generateSql(change.generateStatements(database)[0], database)[0].toSql());
        assertFalse(change.validate(database).hasErrors());
    }

    @Test
    public void onlyAppliesToAthena() {
        DropTableStatement statement = new DropTableStatement(null, "target", "events", false);

        assertTrue(new AthenaDropTableGenerator().supports(statement, new AthenaDatabase()));
        assertFalse(new AthenaDropTableGenerator().supports(statement, new H2Database()));
    }
}
