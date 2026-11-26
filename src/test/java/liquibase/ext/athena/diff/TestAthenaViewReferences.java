package liquibase.ext.athena.diff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import liquibase.CatalogAndSchema;
import liquibase.diff.compare.CompareControl;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.diff.AthenaViewReferences.Relation;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestAthenaViewReferences {
    private static final String CATALOG = "awsdatacatalog";
    private static final Map<Relation, Relation> SCHEMAS = AthenaViewReferences.schemas(
            new CompareControl.SchemaComparison[] { comparison(CATALOG, "src", CATALOG, "tgt") }, CATALOG, CATALOG);

    private static final List<String> SUPPORTED = List.of(
            "SELECT sum(id) OVER w FROM src.departments WINDOW w AS (ORDER BY id)",
            "SELECT members[(SELECT id FROM src.departments)] FROM src.departments",
            "SELECT sum(id) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM src.departments",
            "SELECT count(*) FROM src.departments GROUP BY GROUPING SETS ((id), ())",
            "SELECT count(*) FILTER (WHERE d.id > 0) FROM src.departments d",
            "SELECT transform(members, src -> src.departments.id) FROM src.departments d",
            "SELECT transform(members, tgt -> tgt.departments.id) FROM src.departments d",
            "SELECT d.members[d.id] FROM src.departments d",
            "SELECT 'a_b' LIKE 'a!_b' ESCAPE (SELECT name FROM src.departments)",
            "SELECT d.id FROM src.departments d WHERE ((id > 0) AND (id < 2))",
            "SELECT id FROM src.departments;",
            "SELECT JSON_OBJECT('key': id) FROM src.departments",
            "SELECT '$1 : ?' FROM src.departments",
            "SELECT id FROM src.departments",
            "SELECT * FROM src.departments",
            "SELECT d.id, d.* FROM src.departments d",
            "SELECT d.payload.nested.id FROM src.departments d",
            "SELECT src.departments.id FROM src.departments AS src",
            "SELECT * FROM src.departments d ORDER BY src.departments.id LIMIT 1",
            "SELECT d.* FROM src.departments d ORDER BY src.departments.id LIMIT 1",
            "SELECT id, payload AS src FROM src.departments d ORDER BY src.departments.id LIMIT 1",
            "SELECT id, payload AS tgt FROM src.departments d ORDER BY tgt.departments.id LIMIT 1",
            "SELECT id FROM src.departments WHERE id > 0",
            "SELECT d.id FROM src.departments d JOIN src.departments other ON d.id = other.id",
            "SELECT d.id FROM src.departments d JOIN other.records r ON d.id = r.id",
            "SELECT id FROM src.departments UNION ALL SELECT id FROM src.departments",
            "WITH src AS (SELECT id FROM src.departments) SELECT id FROM src",
            "SELECT src.departments.id FROM (SELECT payload AS departments FROM src.departments d) src",
            "SELECT id FROM src.departments d WHERE EXISTS (SELECT 1 FROM other.records r WHERE r.id = d.id)",
            "SELECT (SELECT max(id) FROM src.departments) AS value",
            "SELECT id FROM (SELECT id FROM src.departments) nested",
            "SELECT count(*) FROM src.departments GROUP BY id HAVING count(*) > 0 ORDER BY id",
            "SELECT sum(d.id) OVER (PARTITION BY d.name ORDER BY d.id) FROM src.departments d",
            "SELECT array_agg(d.id ORDER BY d.id) FROM src.departments d",
            "SELECT id FROM src.departments WHERE name LIKE 'a!_b' ESCAPE '!'",
            "SELECT id FROM src.departments WHERE name LIKE 'a!_b' ESCAPE (SELECT name FROM src.departments)",
            "SELECT substr(name, 1, 1) FROM src.departments",
            "SELECT id FROM src.departments WHERE id IN (SELECT id FROM src.departments)",
            "SELECT CASE WHEN id > 0 THEN name ELSE 'src.departments' END FROM src.departments",
            "SELECT 'é😀 src.departments' AS text FROM src.departments",
            "SELECT id FROM src /* keep schema comment */ . departments -- tail",
            "SELECT id FROM \"SRC\".\"departments\"",
            "SELECT id FROM awsdatacatalog.src.departments",
            "SELECT id FROM external.src.departments JOIN src.departments d ON true",
            "SELECT id FROM departments JOIN other.records r ON true");

    private static final List<String> UNSAFE_FORMS = List.of(
            "SELECT %s.id FROM src.departments",
            "SELECT * FROM src.departments ORDER BY %s.id LIMIT 1",
            "SELECT src.departments.* FROM src.departments ORDER BY %s.id LIMIT 1",
            "SELECT id, payload AS src FROM src.departments ORDER BY %s.id LIMIT 1",
            "SELECT id, src FROM src.departments ORDER BY %s.id LIMIT 1",
            "SELECT * FROM src.departments WHERE %s.id > 0",
            "SELECT id FROM src.departments JOIN other.records r ON r.id = %s.id",
            "SELECT count(*) FROM src.departments GROUP BY %s.id",
            "SELECT count(*) FROM src.departments HAVING count(*) > %s.id",
            "SELECT sum(id) OVER (ORDER BY %s.id) FROM src.departments",
            "SELECT array_agg(id ORDER BY %s.id) FROM src.departments",
            "SELECT count(*) FILTER (WHERE %s.id > 0) FROM src.departments",
            "SELECT (SELECT %s.id) FROM src.departments",
            "WITH nested AS (SELECT %s.id FROM src.departments) SELECT * FROM nested",
            "SELECT name FROM src.departments WHERE name LIKE 'x' ESCAPE %s.name");

    private static final List<String> UNSUPPORTED = List.of(
            "SELECT FROM src.departments", "SELECT id FROM src.departments; SELECT 1",
            "SELECT id FROM src.departments trailing junk", "DELETE FROM src.departments",
            "CREATE TABLE src.departments(id INT)", "SELECT id @ FROM src.departments",
            "SELECT id FROM src.departments WHERE", "SELECT id. FROM src.departments",
            "SELECT transform(members, src -> src.departments.id) FROM src.departments",
            "SELECT members[src.departments.id] FROM src.departments",
            "SELECT POSITION('x' IN name) FROM src.departments",
            "SELECT CAST(ROW(payload) AS ROW(nested ROW(departments ROW(id BIGINT)))) FROM src.departments d",
            "SELECT CAST(ROW (payload) AS ROW(nested ROW(departments ROW(id BIGINT)))) FROM src.departments d",
            "SELECT :value FROM src.departments", "SELECT * FROM src.departments WHERE id = ?",
            "[CANNOT READ VIEW DEFINITION]");

    @Test
    public void mapsOnlyRelationNames() throws DatabaseException {
        for (String form : SUPPORTED) {
            for (String sql : List.of(form, form.replace("src.departments", "src.\"departments\""))) {
                String expected = sql.replace("FROM src.", "FROM \"tgt\".")
                        .replace("JOIN src.", "JOIN \"tgt\".")
                        .replace("FROM src /*", "FROM \"tgt\" /*")
                        .replace("\"SRC\"", "\"tgt\"").replace("awsdatacatalog.src.", "\"awsdatacatalog\".\"tgt\".");
                assertEquals(expected, remap(sql));
            }
        }
    }

    @Test
    public void rejectsQualifiedReferencesWhoseBindingWouldChange() {
        for (String prefix : List.of("src.departments", "tgt.departments", "awsdatacatalog.src.departments",
                "awsdatacatalog.tgt.departments")) {
            for (String form : UNSAFE_FORMS) {
                String sql = form.formatted(prefix);
                for (String nested : List.of(sql, "SELECT * FROM (" + sql + ") nested",
                        "WITH nested AS (" + sql + ") SELECT * FROM nested")) {
                    assertThrows(nested, DatabaseException.class, () -> remap(nested));
                }
            }
        }
    }

    @Test
    public void rejectsWhatCannotBeAnalysedCompletely() {
        for (String sql : UNSUPPORTED) {
            assertThrows(sql, DatabaseException.class, () -> remap(sql));
        }
    }

    @Test
    public void keepsOffsetsCommentsAndLiterals() throws DatabaseException {
        for (String prefix : List.of("SELECT 'é😀' AS text\tFROM\t",
                "-- src.departments\r\nSELECT 'é😀 src.departments' AS text\r\nFROM ")) {
            assertEquals(prefix + "\"tgt\".departments -- tail", remap(prefix + "src.departments -- tail"));
        }
    }

    @Test
    public void mapsCatalogsAndRejectsCrossCatalogCapture() throws DatabaseException {
        Map<Relation, Relation> schemas = AthenaViewReferences.schemas(new CompareControl.SchemaComparison[] {
                comparison("source_catalog", "src", "target_catalog", "tgt") }, "source_catalog", "target_catalog");
        for (String relation : List.of("src.departments", "source_catalog.src.departments",
                "\"source_catalog\".\"src\".departments")) {
            assertEquals("SELECT id FROM \"target_catalog\".\"tgt\".departments",
                    AthenaViewReferences.remap("SELECT id FROM " + relation, schemas, "source_catalog", new HashMap<>()));
            assertThrows(DatabaseException.class, () -> AthenaViewReferences.remap(
                    "SELECT tgt.departments.id FROM " + relation, schemas, "source_catalog", new HashMap<>()));
        }
    }

    @Test
    public void usesDefaultCatalogsAndSkipsSchemasComparedWithThemselves() {
        Map<Relation, Relation> schemas = AthenaViewReferences.schemas(new CompareControl.SchemaComparison[] {
                comparison(null, "src", null, "tgt"), comparison("shared", "same", "shared", "SAME"),
                new CompareControl.SchemaComparison(new CatalogAndSchema(null, null), new CatalogAndSchema(null, "tgt")) },
                "Source_Catalog", "target_catalog");
        assertEquals(Map.of(new Relation("source_catalog", "src", ""), new Relation("target_catalog", "tgt", "")), schemas);
        assertTrue(AthenaViewReferences.schemas(null, CATALOG, CATALOG).isEmpty());
    }

    @Test
    public void reportsTheRelationsWhichTheResultReads() throws DatabaseException {
        Map<Relation, Relation> references = new HashMap<>();
        AthenaViewReferences.remap("WITH local AS (SELECT * FROM src.departments) SELECT * FROM local "
                + "JOIN other.records r ON true JOIN \"Base_View\" b ON true", SCHEMAS, CATALOG, references);
        assertEquals(Map.of(
                new Relation(CATALOG, "src", "departments"), new Relation(CATALOG, "tgt", "departments"),
                new Relation(CATALOG, "other", "records"), new Relation(CATALOG, "other", "records"),
                new Relation("", "", "base_view"), new Relation("", "", "base_view")), references);
    }

    @Test
    public void commonTableExpressionsHideRelationsOnlyAfterTheirDefinition() throws DatabaseException {
        Relation view = new Relation("", "", "base_view");
        for (String sql : List.of("SELECT * FROM base_view",
                "WITH base_view AS (SELECT * FROM base_view) SELECT * FROM base_view",
                "WITH nested AS (SELECT * FROM base_view), base_view AS (SELECT 1) SELECT * FROM nested",
                "SELECT * FROM (WITH base_view AS (SELECT 1) SELECT * FROM base_view) nested JOIN base_view ON true")) {
            assertEquals(sql, Set.of(view), references(sql));
        }
        for (String sql : List.of("WITH base_view AS (SELECT 1) SELECT * FROM base_view",
                "WITH RECURSIVE \"BASE_VIEW\" AS (SELECT 1) SELECT * FROM base_view UNION ALL SELECT * FROM base_view",
                "WITH base_view AS (SELECT 1), nested AS (SELECT * FROM base_view) SELECT * FROM nested")) {
            assertEquals(sql, Set.of(), references(sql));
        }
    }

    @Test
    public void comparesDefinitionsByTokensWithoutChangingLiterals() throws DatabaseException {
        assertEquals(AthenaViewReferences.canonical("SELECT 'é😀 SRC.departments' AS text FROM \"tgt\".departments;"),
                AthenaViewReferences.canonical("select 'é😀 SRC.departments' as text /* comment */ from TGT.departments"));
        assertNotEquals(AthenaViewReferences.canonical("SELECT 'SRC.departments' FROM tgt.departments"),
                AthenaViewReferences.canonical("SELECT 'src.departments' FROM tgt.departments"));
    }

    private static Set<Relation> references(String sql) throws DatabaseException {
        Map<Relation, Relation> references = new HashMap<>();
        AthenaViewReferences.remap(sql, Map.of(), CATALOG, references);
        return references.keySet();
    }

    private static String remap(String sql) throws DatabaseException {
        return AthenaViewReferences.remap(sql, SCHEMAS, CATALOG, new HashMap<>());
    }

    private static CompareControl.SchemaComparison comparison(String referenceCatalog, String referenceSchema,
            String comparisonCatalog, String comparisonSchema) {
        return new CompareControl.SchemaComparison(new CatalogAndSchema(referenceCatalog, referenceSchema),
                new CatalogAndSchema(comparisonCatalog, comparisonSchema));
    }
}
