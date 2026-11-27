package liquibase.ext.athena.sqlgenerator;

import java.util.List;

import liquibase.change.core.ModifyDataTypeChange;
import liquibase.database.core.H2Database;
import liquibase.ext.athena.database.AthenaDatabase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TestAthenaModifyDataTypeGenerator {
    @Test
    public void reportsAColumnTypeChangeAsUnsupportedOnlyForAthena() {
        ModifyDataTypeChange change = new ModifyDataTypeChange();
        change.setTableName("events");
        change.setColumnName("id");
        change.setNewDataType("BIGINT");

        assertEquals(List.of("Athena does not support modifyDataType"), change.validate(new AthenaDatabase()).getErrorMessages());
        assertFalse(change.validate(new H2Database()).hasErrors());
    }
}
