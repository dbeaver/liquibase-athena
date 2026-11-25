package liquibase.ext.athena.sqlgenerator;

import java.util.List;

import liquibase.change.AddColumnConfig;
import liquibase.change.Change;
import liquibase.change.core.AddDefaultValueChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddNotNullConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateViewChange;
import liquibase.change.core.DropForeignKeyConstraintChange;
import liquibase.change.core.DropIndexChange;
import liquibase.change.core.DropNotNullConstraintChange;
import liquibase.change.core.DropViewChange;
import liquibase.change.core.ModifyDataTypeChange;
import liquibase.change.core.SetColumnRemarksChange;
import liquibase.change.core.SetTableRemarksChange;
import liquibase.database.core.H2Database;
import liquibase.ext.athena.database.AthenaDatabase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestAthenaUnsupportedGenerator {
    @Test
    public void reportsChangesWhichOnlyHaveGenericSqlAsUnsupported() {
        for (Change change : genericChanges()) {
            List<String> errors = change.validate(new AthenaDatabase()).getErrorMessages();

            assertEquals(errors.toString(), 1, errors.size());
            assertTrue(errors.toString(), errors.get(0).startsWith("Athena does not support "));
            assertFalse(change.validate(new H2Database()).getErrorMessages().toString(),
                    change.validate(new H2Database()).hasErrors());
        }
    }

    @Test
    public void leavesRemarksUnsupportedAndViewsSupported() {
        SetColumnRemarksChange columnRemarks = new SetColumnRemarksChange();
        columnRemarks.setTableName("events");
        columnRemarks.setColumnName("id");
        columnRemarks.setRemarks("identifier");
        SetTableRemarksChange tableRemarks = new SetTableRemarksChange();
        tableRemarks.setTableName("events");
        tableRemarks.setRemarks("events");
        CreateViewChange createView = new CreateViewChange();
        createView.setSchemaName("target");
        createView.setViewName("events_view");
        createView.setSelectQuery("SELECT 1 AS id");
        createView.setReplaceIfExists(true);
        DropViewChange dropView = new DropViewChange();
        dropView.setSchemaName("target");
        dropView.setViewName("events_view");

        assertTrue(columnRemarks.validate(new AthenaDatabase()).hasErrors());
        assertTrue(tableRemarks.validate(new AthenaDatabase()).hasErrors());
        assertFalse(createView.validate(new AthenaDatabase()).hasErrors());
        assertFalse(dropView.validate(new AthenaDatabase()).hasErrors());
    }


    private static List<Change> genericChanges() {
        ModifyDataTypeChange type = new ModifyDataTypeChange();
        type.setTableName("events");
        type.setColumnName("id");
        type.setNewDataType("BIGINT");
        AddNotNullConstraintChange notNull = new AddNotNullConstraintChange();
        notNull.setTableName("events");
        notNull.setColumnName("id");
        notNull.setColumnDataType("INT");
        DropNotNullConstraintChange nullable = new DropNotNullConstraintChange();
        nullable.setTableName("events");
        nullable.setColumnName("id");
        nullable.setColumnDataType("INT");
        AddDefaultValueChange defaultValue = new AddDefaultValueChange();
        defaultValue.setTableName("events");
        defaultValue.setColumnName("id");
        defaultValue.setDefaultValueNumeric("1");
        AddPrimaryKeyChange primaryKey = new AddPrimaryKeyChange();
        primaryKey.setTableName("events");
        primaryKey.setColumnNames("id");
        primaryKey.setConstraintName("pk_events");
        AddForeignKeyConstraintChange foreignKey = new AddForeignKeyConstraintChange();
        foreignKey.setBaseTableName("events");
        foreignKey.setBaseColumnNames("owner_id");
        foreignKey.setReferencedTableName("owners");
        foreignKey.setReferencedColumnNames("id");
        foreignKey.setConstraintName("fk_events_owner");
        DropForeignKeyConstraintChange dropForeignKey = new DropForeignKeyConstraintChange();
        dropForeignKey.setBaseTableName("events");
        dropForeignKey.setConstraintName("fk_events_owner");
        AddUniqueConstraintChange unique = new AddUniqueConstraintChange();
        unique.setTableName("events");
        unique.setColumnNames("id");
        unique.setConstraintName("uq_events");
        CreateIndexChange index = new CreateIndexChange();
        index.setTableName("events");
        index.setIndexName("ix_events");
        AddColumnConfig indexColumn = new AddColumnConfig();
        indexColumn.setName("id");
        index.addColumn(indexColumn);
        DropIndexChange dropIndex = new DropIndexChange();
        dropIndex.setTableName("events");
        dropIndex.setIndexName("ix_events");
        return List.of(type, notNull, nullable, defaultValue, primaryKey, foreignKey, dropForeignKey, unique, index,
                dropIndex);
    }
}
