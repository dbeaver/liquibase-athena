package liquibase.ext.athena.diff;

import java.util.List;

import liquibase.CatalogAndSchema;
import liquibase.change.Change;
import liquibase.change.core.CreateViewChange;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.ChangeGeneratorFactory;
import liquibase.ext.athena.change.CreateAthenaViewChange;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.structure.core.View;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestAthenaViewChangeGenerators {
    private static final CompareControl.SchemaComparison[] COMPARISONS = { new CompareControl.SchemaComparison(
            new CatalogAndSchema("awsdatacatalog", "src"), new CatalogAndSchema("awsdatacatalog", "tgt")) };

    @Test
    public void missingViewReadsTheComparisonSchema() {
        CreateAthenaViewChange change = missing("SELECT d.id FROM src.departments d JOIN other.records r ON d.id = r.id",
                COMPARISONS);

        assertEquals("SELECT d.id FROM \"tgt\".departments d JOIN other.records r ON d.id = r.id", change.getSelectQuery());
        assertEquals("departments_view", change.getViewName());
        assertNull(change.getReferenceError());
        assertFalse(change.validate(database()).hasErrors());
        assertEquals("createView", change.getSerializedObjectName());
        assertFalse(change.getSerializableFields().contains("referenceError"));
    }

    @Test
    public void changedViewReadsTheComparisonSchema() {
        View view = view("SELECT id FROM src.departments");
        ObjectDifferences differences = new ObjectDifferences(new CompareControl());
        differences.addDifference("definition", view.getDefinition(), "SELECT name FROM tgt.departments");
        Change[] changes = ChangeGeneratorFactory.getInstance().fixChanged(view, differences,
                new DiffOutputControl(true, true, false, COMPARISONS), database(), database());

        CreateAthenaViewChange change = (CreateAthenaViewChange) changes[0];
        assertEquals("SELECT id FROM \"tgt\".departments", change.getSelectQuery());
        assertEquals(Boolean.TRUE, change.getReplaceIfExists());
    }

    @Test
    public void reportsAViewWhichCannotBeMappedInsteadOfChangingIt() {
        for (String sql : List.of("SELECT src.departments.id FROM src.departments",
                "SELECT POSITION('x' IN name) FROM src.departments", "[CANNOT READ VIEW DEFINITION]",
                "COULD NOT DETERMINE VIEW QUERY")) {
            CreateAthenaViewChange change = missing(sql, COMPARISONS);

            assertEquals(sql, change.getSelectQuery());
            assertTrue(sql, change.getReferenceError().startsWith("Cannot map Athena view departments_view to the target schema: "));
            assertTrue(sql, change.validate(database()).getErrorMessages().contains(change.getReferenceError()));
        }
    }

    @Test
    public void keepsAQueryWhichCannotReadAMappedSchema() {
        for (String sql : List.of("SELECT", "SELECT id FROM", "SELECT POSITION('x' IN name) FROM other.records",
                "SELECT * FROM other.records WHERE id = ?",
                "WITH r AS (SELECT POSITION('x' IN name) AS p FROM other.records) SELECT p FROM r",
                " (SELECT POSITION('x' IN name) FROM other.records)")) {
            CreateAthenaViewChange change = missing(sql, COMPARISONS);

            assertEquals(sql, change.getSelectQuery());
            assertNull(sql, change.getReferenceError());
        }
    }

    @Test
    public void keepsTheDefinitionWhenSchemasAreNotMapped() {
        String sql = "SELECT src.departments.id FROM src.departments";
        CompareControl.SchemaComparison[] sameSchema = { new CompareControl.SchemaComparison(
                new CatalogAndSchema("awsdatacatalog", "src"), new CatalogAndSchema("awsdatacatalog", "src")) };
        for (CompareControl.SchemaComparison[] comparisons : new CompareControl.SchemaComparison[][] { null, sameSchema }) {
            CreateAthenaViewChange change = missing(sql, comparisons);

            assertEquals(sql, change.getSelectQuery());
            assertNull(change.getReferenceError());
        }
    }

    private static CreateAthenaViewChange missing(String sql, CompareControl.SchemaComparison[] comparisons) {
        Change[] changes = ChangeGeneratorFactory.getInstance().fixMissing(view(sql),
                new DiffOutputControl(true, true, false, comparisons), database(), database());
        assertEquals(1, changes.length);
        assertTrue(changes[0] instanceof CreateViewChange);
        return (CreateAthenaViewChange) changes[0];
    }

    private static View view(String sql) {
        View view = new View("awsdatacatalog", "src", "departments_view");
        view.setDefinition(sql);
        return view;
    }

    private static AthenaDatabase database() {
        AthenaDatabase database = new AthenaDatabase();
        database.setDefaultCatalogName("awsdatacatalog");
        return database;
    }
}
