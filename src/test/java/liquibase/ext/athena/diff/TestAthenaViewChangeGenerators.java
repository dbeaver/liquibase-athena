package liquibase.ext.athena.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import liquibase.CatalogAndSchema;
import liquibase.change.Change;
import liquibase.change.core.CreateViewChange;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.diff.DiffResult;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.ObjectChangeFilter;
import liquibase.diff.output.changelog.ChangeGeneratorFactory;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.ext.athena.change.CreateAthenaViewChange;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.EmptyDatabaseSnapshot;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import liquibase.structure.core.Table;
import liquibase.structure.core.View;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestAthenaViewChangeGenerators {
    private static final String CANNOT_MAP = "Cannot map Athena view ";
    private static final String DEPENDS = ": it depends on itself or on a view which cannot be migrated";
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
        assertTrue(change.getSerializableFields().contains("referenceError"));
    }

    @Test
    public void changedViewReadsTheComparisonSchema() {
        View view = view("departments_view", "SELECT id FROM src.departments");
        Change[] changes = ChangeGeneratorFactory.getInstance().fixChanged(view,
                differences(view, "SELECT name FROM tgt.departments"),
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
            assertTrue(sql, change.getReferenceError().startsWith(CANNOT_MAP + "departments_view to the target schema: "));
            assertTrue(sql, change.validate(database()).getErrorMessages().contains(change.getReferenceError()));
        }
    }

    @Test
    public void rejectsQueriesWhoseDependenciesCannotBeAnalysed() {
        for (String sql : List.of("SELECT", "SELECT id FROM", "SELECT POSITION('x' IN name) FROM other.records",
                "WITH r AS (SELECT POSITION('x' IN name) AS p FROM other.records) SELECT p FROM r",
                " (SELECT POSITION('x' IN name) FROM other.records)")) {
            CreateAthenaViewChange change = missing(sql, COMPARISONS);

            assertEquals(sql, change.getSelectQuery());
            assertTrue(sql, change.getReferenceError().startsWith(CANNOT_MAP + "departments_view to the target schema: "));
            assertTrue(sql, change.validate(database()).hasErrors());
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

    @Test
    public void createsAViewAfterTheViewsItReads() throws Exception {
        View dependent = view("a_view", "SELECT id FROM src.z_view");
        View dependency = view("z_view", "SELECT id FROM src.m_view");
        View base = view("m_view", "SELECT 1 AS id");
        View other = view("b_view", "SELECT id FROM src.departments");

        List<CreateViewChange> changes = generate(List.of(dependent, dependency, base, other), Map.of());

        assertEquals(List.of("b_view", "m_view", "z_view", "a_view"), names(changes));
        assertEquals("SELECT id FROM \"tgt\".z_view", changes.get(3).getSelectQuery());
        assertTrue(rejected(changes).isEmpty());
    }

    @Test
    public void readsUnqualifiedNamesFromTheSchemaOfTheView() throws Exception {
        for (String sql : List.of("SELECT * FROM z_view", "SELECT * FROM \"Z_VIEW\" b JOIN src.departments d ON true",
                "WITH z_view AS (SELECT * FROM z_view) SELECT * FROM z_view",
                "WITH nested AS (SELECT * FROM z_view), z_view AS (SELECT 1) SELECT * FROM nested",
                "SELECT * FROM (WITH z_view AS (SELECT 1) SELECT * FROM z_view) nested JOIN z_view ON true")) {
            assertEquals(sql, List.of("z_view", "a_view"), names(generate(
                    List.of(view("a_view", sql), view("z_view", "SELECT id FROM src.departments")), Map.of())));
        }
        for (String sql : List.of("WITH z_view AS (SELECT 1) SELECT * FROM z_view",
                "WITH a_view AS (SELECT 1) SELECT * FROM a_view",
                "WITH RECURSIVE a_view(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM a_view WHERE id < 3) SELECT * FROM a_view",
                "WITH z_view AS (SELECT 1), nested AS (SELECT * FROM z_view) SELECT * FROM nested")) {
            List<CreateViewChange> changes = generate(
                    List.of(view("a_view", sql), view("z_view", "SELECT id FROM src.departments")), Map.of());
            assertEquals(sql, List.of("a_view", "z_view"), names(changes));
            assertTrue(sql, rejected(changes).isEmpty());
        }
    }

    @Test
    public void recursiveCtesDoNotDependOnEquallyNamedViews() throws Exception {
        String query = "WITH RECURSIVE z_view(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM z_view WHERE id < 3) "
                + "SELECT * FROM z_view";
        for (String other : List.of("SELECT id FROM src.a_view", "SELECT src.departments.id FROM src.departments")) {
            List<CreateViewChange> changes = generate(List.of(view("a_view", query), view("z_view", other)), Map.of());

            assertEquals(List.of("a_view", "z_view"), names(changes));
            assertFalse(rejected(changes).containsKey("a_view"));
            assertEquals(query, changes.get(0).getSelectQuery());
        }
    }

    @Test
    public void rejectsViewsWhichDependOnEachOtherOrOnThemselves() throws Exception {
        List<CreateViewChange> changes = generate(List.of(view("a_view", "SELECT id FROM src.b_view"),
                view("b_view", "SELECT id FROM src.a_view"), view("c_view", "SELECT * FROM tgt.c_view"),
                view("d_view", "SELECT id FROM src.departments")), Map.of());

        assertEquals(List.of("d_view", "a_view", "b_view", "c_view"), names(changes));
        assertEquals(Map.of("a_view", "Cannot create Athena view a_view" + DEPENDS,
                "b_view", "Cannot create Athena view b_view" + DEPENDS,
                "c_view", "Cannot create Athena view c_view" + DEPENDS), rejected(changes));
    }

    @Test
    public void rejectsUnqualifiedSelfReferencesAndTheirDependents() throws Exception {
        for (String query : List.of("SELECT * FROM a_view", "SELECT * FROM \"A_VIEW\"",
                "WITH unrelated AS (SELECT 1) SELECT * FROM a_view")) {
            List<CreateViewChange> changes = generate(List.of(view("a_view", query),
                    view("top_view", "SELECT * FROM a_view"), view("independent_view", "SELECT 1 AS id")), Map.of());

            assertEquals(Set.of("a_view", "top_view"), rejected(changes).keySet());
            assertEquals("Cannot create Athena view a_view" + DEPENDS, rejected(changes).get("a_view"));
            assertEquals("independent_view", changes.get(0).getViewName());
        }
    }

    @Test
    public void rejectsViewsBuiltOnAViewWhichCannotBeMapped() throws Exception {
        List<CreateViewChange> changes = generate(List.of(view("top_view", "SELECT id FROM src.middle_view"),
                view("middle_view", "SELECT id FROM src.base_view"),
                view("base_view", "SELECT POSITION('x' IN name) AS id FROM src.departments"),
                view("independent_view", "SELECT id FROM src.departments")), Map.of());

        Map<String, String> rejected = rejected(changes);
        assertEquals(Set.of("base_view", "middle_view", "top_view"), rejected.keySet());
        assertTrue(rejected.get("base_view").startsWith(CANNOT_MAP + "base_view to the target schema: "));
        assertEquals("Cannot create Athena view top_view" + DEPENDS, rejected.get("top_view"));
        assertEquals("independent_view", names(changes).get(0));
    }

    @Test
    public void rejectsUnparsedViewsWithUnqualifiedDependencies() throws Exception {
        String query = "SELECT CAST(ROW(ROW(ROW(id))) AS ROW(nested ROW(departments ROW(id BIGINT)))) AS payload FROM z_view";
        for (String base : List.of("SELECT 1 AS id", "SELECT src.departments.id FROM src.departments")) {
            List<CreateViewChange> changes = generate(List.of(view("a_view", query), view("z_view", base),
                    view("top_view", "SELECT * FROM a_view"), view("independent_view", "SELECT 1 AS id")), Map.of());

            Map<String, String> rejected = rejected(changes);
            assertTrue(rejected.getOrDefault("a_view", "").startsWith(CANNOT_MAP + "a_view to the target schema: "));
            assertEquals("Cannot create Athena view top_view" + DEPENDS, rejected.get("top_view"));
            assertFalse(rejected.containsKey("independent_view"));
            assertEquals(query, changes.stream().filter(change -> "a_view".equals(change.getViewName()))
                    .findFirst().orElseThrow().getSelectQuery());
        }
    }

    @Test
    public void rejectsViewsBuiltOnAnUnparsedExternalQuery() throws Exception {
        List<CreateViewChange> changes = generate(List.of(view("a_view", "SELECT id FROM src.z_view"),
                view("z_view", "SELECT POSITION('x' IN name) AS id FROM other.records")), Map.of());

        assertEquals(Set.of("a_view", "z_view"), rejected(changes).keySet());
        assertEquals("Cannot create Athena view a_view" + DEPENDS, rejected(changes).get("a_view"));
    }

    @Test
    public void replacesAViewBeforeCreatingTheViewsWhichReadIt() throws Exception {
        View created = view("a_view", "SELECT id, name FROM src.z_view");
        View replaced = view("z_view", "SELECT id, name FROM src.departments");
        View independent = view("b_view", "SELECT id FROM src.departments");

        List<CreateViewChange> changes = generate(List.of(created, independent),
                Map.of(replaced, "SELECT id FROM tgt.departments"));

        assertEquals(List.of("b_view", "z_view", "a_view"), names(changes));
        assertEquals(Boolean.TRUE, changes.get(1).getReplaceIfExists());
        assertEquals("SELECT id, name FROM \"tgt\".departments", changes.get(1).getSelectQuery());
    }

    @Test
    public void leavesAViewWhichDiffersOnlyBySchemaNames() throws Exception {
        for (String sql : List.of("SELECT id FROM src.departments",
                "SELECT d.id FROM src.departments d JOIN other.records r ON d.id = r.id",
                "SELECT id FROM awsdatacatalog.src.departments", "WITH src AS (SELECT id FROM src.departments) SELECT id FROM src",
                "SELECT 'src.departments' AS text FROM src.\"departments\"")) {
            String target = sql.replace("FROM src.", "from TGT.").replace("awsdatacatalog.src.", "awsdatacatalog.tgt.")
                    + " /* comment */";
            assertEquals(sql, List.of("new_view"), names(generate(List.of(view("new_view", "SELECT 1 AS id")),
                    Map.of(view("departments_view", sql), target))));
        }
    }

    @Test
    public void replacesAViewWhoseDefinitionReallyDiffers() throws Exception {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("SELECT id FROM src.departments", "SELECT name FROM tgt.departments");
        definitions.put("SELECT 'SRC.departments' AS text FROM src.departments", "SELECT 'src.departments' AS text FROM tgt.departments");
        definitions.put("SELECT id FROM tgt.departments", "SELECT id FROM tgt.departments ");
        definitions.put("SELECT src.departments.id FROM src.departments", "SELECT tgt.departments.id FROM tgt.departments");
        definitions.put("SELECT POSITION('x' IN name) FROM src.departments", "SELECT POSITION('x' IN name) FROM tgt.departments");
        for (Map.Entry<String, String> definition : definitions.entrySet()) {
            assertEquals(definition.getKey(), List.of("departments_view"), names(generate(List.of(),
                    Map.of(view("departments_view", definition.getKey()), definition.getValue()))));
        }
    }

    @Test
    public void replacesAViewWhenTheColumnsOfItsRelationsDiffer() throws Exception {
        View view = view("departments_view", "SELECT * FROM src.departments");
        ObjectDifferences differences = differences(view, "SELECT * FROM tgt.departments");
        DiffResult result = new DiffResult(snapshot("src", "id", "name"), snapshot("tgt", "id", "name", "extra"),
                new CompareControl(COMPARISONS, "tables,views"));
        result.addChangedObject(view, differences);

        assertEquals(List.of("departments_view"), names(generate(result, null)));
    }

    @Test
    public void keepsChangedViewsWhenColumnMetadataIsUnavailable() throws Exception {
        List<DatabaseSnapshot> sources = List.of(new EmptyDatabaseSnapshot(database()), snapshot("src"), snapshot("src", "id"));
        List<DatabaseSnapshot> targets = List.of(new EmptyDatabaseSnapshot(database()), snapshot("tgt"), snapshot("tgt", "id"));
        for (int source = 0; source < sources.size(); source++) {
            for (int target = 0; target < targets.size(); target++) {
                if (source == 2 && target == 2) {
                    continue;
                }
                View view = view("departments_view", "SELECT * FROM src.departments");
                DiffResult result = new DiffResult(sources.get(source), targets.get(target),
                        new CompareControl(COMPARISONS, "views"));
                result.addChangedObject(view, differences(view, "SELECT * FROM tgt.departments"));

                assertEquals("metadata " + source + "/" + target,
                        List.of("departments_view"), names(generate(result, null)));
            }
        }
    }

    @Test
    public void resolvesUnqualifiedRelationsBeforeComparingViewBindings() throws Exception {
        Map<String, List<String>> known = Map.of("departments", List.of("id"), "staff", List.of("id"));
        Map<String, List<String>> filtered = Map.of("departments", List.of("id"));
        for (Map<String, List<String>> source : List.of(known, filtered)) {
            for (Map<String, List<String>> target : List.of(known, filtered,
                    Map.of("departments", List.of("id"), "staff", List.of("id", "extra")),
                    Map.of("departments", List.of("id"), "staff", List.<String>of()))) {
                View view = view("departments_view", "SELECT * FROM src.departments d JOIN staff s ON true");
                DiffResult result = new DiffResult(snapshot("src", source), snapshot("tgt", target),
                        new CompareControl(COMPARISONS, "tables,views"));
                result.addChangedObject(view, differences(view, "SELECT * FROM tgt.departments d JOIN staff s ON true"));

                boolean equivalent = source.containsKey("staff") && source.get("staff").equals(target.get("staff"));
                assertEquals(source + " / " + target, equivalent ? List.of() : List.of("departments_view"),
                        names(generate(result, null)));
            }
        }
    }

    @Test
    public void replacesAViewWhichAlsoDiffersInAnotherProperty() throws Exception {
        View view = view("departments_view", "SELECT id FROM src.departments");
        ObjectDifferences differences = differences(view, "SELECT id FROM tgt.departments");
        differences.addDifference("remarks", "reference", "compared");
        DiffResult result = new DiffResult(snapshot("src", "id"), snapshot("tgt", "id"),
                new CompareControl(COMPARISONS, "tables,views"));
        result.addChangedObject(view, differences);

        List<CreateViewChange> changes = generate(result, null);
        assertEquals(List.of("departments_view"), names(changes));
        assertEquals("SELECT id FROM \"tgt\".departments", changes.get(0).getSelectQuery());
    }

    @Test
    public void leavesOutViewsExcludedByTheFilter() throws Exception {
        View excluded = view("z_view", "SELECT POSITION('x' IN name) AS id FROM src.departments");
        DiffResult result = new DiffResult(snapshot("src", "id"), snapshot("tgt", "id"),
                new CompareControl(COMPARISONS, "tables,views"));
        result.addMissingObject(view("a_view", "SELECT id FROM src.z_view"));
        result.addMissingObject(excluded);

        List<CreateViewChange> changes = generate(result, new ObjectChangeFilter() {
            @Override
            public boolean includeMissing(DatabaseObject object, Database referenceDatabase, Database comparisonDatabase) {
                return object != excluded;
            }

            @Override
            public boolean includeUnexpected(DatabaseObject object, Database referenceDatabase, Database comparisonDatabase) {
                return true;
            }

            @Override
            public boolean includeChanged(DatabaseObject object, ObjectDifferences differences, Database referenceDatabase,
                    Database comparisonDatabase) {
                return true;
            }

            @Override
            public boolean include(DatabaseObject object) {
                return true;
            }
        });

        assertEquals(List.of("a_view"), names(changes));
        assertTrue(rejected(changes).isEmpty());
    }

    private static List<CreateViewChange> generate(List<View> missing, Map<View, String> changed) throws Exception {
        DiffResult result = new DiffResult(snapshot("src", "id", "name"), snapshot("tgt", "id", "name"),
                new CompareControl(COMPARISONS, "tables,views"));
        missing.forEach(result::addMissingObject);
        changed.forEach((view, definition) -> result.addChangedObject(view, differences(view, definition)));
        return generate(result, null);
    }

    private static List<CreateViewChange> generate(DiffResult result, ObjectChangeFilter filter) {
        DiffOutputControl control = new DiffOutputControl(true, true, false, COMPARISONS);
        control.setObjectChangeFilter(filter);
        List<CreateViewChange> changes = new ArrayList<>();
        for (ChangeSet changeSet : new DiffToChangeLog(result, control).generateChangeSets()) {
            assertEquals(1, changeSet.getChanges().size());
            // a view with other differences also gets changes such as setTableRemarks
            if (changeSet.getChanges().get(0) instanceof CreateViewChange view) {
                changes.add(view);
            }
        }
        return changes;
    }

    private static List<String> names(List<CreateViewChange> changes) {
        return changes.stream().map(CreateViewChange::getViewName).toList();
    }

    private static Map<String, String> rejected(List<CreateViewChange> changes) {
        Map<String, String> rejected = new LinkedHashMap<>();
        for (CreateViewChange change : changes) {
            if (((CreateAthenaViewChange) change).getReferenceError() != null) {
                rejected.put(change.getViewName(), ((CreateAthenaViewChange) change).getReferenceError());
            }
        }
        return rejected;
    }

    private static CreateAthenaViewChange missing(String sql, CompareControl.SchemaComparison[] comparisons) {
        Change[] changes = ChangeGeneratorFactory.getInstance().fixMissing(view("departments_view", sql),
                new DiffOutputControl(true, true, false, comparisons), database(), database());
        assertEquals(1, changes.length);
        return (CreateAthenaViewChange) changes[0];
    }

    private static ObjectDifferences differences(View view, String comparedDefinition) {
        return new ObjectDifferences(new CompareControl()).addDifference("definition", view.getDefinition(), comparedDefinition);
    }

    private static View view(String name, String sql) {
        View view = new View("awsdatacatalog", "src", name);
        view.setDefinition(sql);
        return view;
    }

    private static AthenaDatabase database() {
        AthenaDatabase database = new AthenaDatabase();
        database.setDefaultCatalogName("awsdatacatalog");
        return database;
    }

    private static DatabaseSnapshot snapshot(String schema, String... columns) throws Exception {
        return snapshot(schema, Map.of("departments", List.of(columns)));
    }

    private static DatabaseSnapshot snapshot(String schema, Map<String, List<String>> relations) throws Exception {
        List<Table> tables = new ArrayList<>();
        for (Map.Entry<String, List<String>> relation : relations.entrySet()) {
            Table table = new Table("awsdatacatalog", schema, relation.getKey());
            relation.getValue().forEach(column -> table.addColumn(new Column().setName(column)));
            tables.add(table);
        }
        return new EmptyDatabaseSnapshot(database()) {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends DatabaseObject> Set<T> get(Class<T> type) {
                return type == Table.class ? (Set<T>) Set.copyOf(tables) : Set.of();
            }
        };
    }
}
