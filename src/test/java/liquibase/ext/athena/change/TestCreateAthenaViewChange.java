package liquibase.ext.athena.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import liquibase.change.core.CreateViewChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.serializer.ChangeLogSerializerFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestCreateAthenaViewChange {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectedViewsKeepTheirValidationErrorAfterSerialization() throws Exception {
        for (String extension : List.of("xml", "json", "yaml")) {
            for (String reason : List.of("Cannot map Athena view departments_view to the target schema",
                    "Cannot create Athena view departments_view: it depends on a view which cannot be migrated")) {
                CreateAthenaViewChange change = change();
                change.rejectReferences(reason);
                CreateAthenaViewChange restored = (CreateAthenaViewChange) roundTrip(change, extension);

                assertEquals(reason, restored.getReferenceError());
                assertEquals(change.getSelectQuery(), restored.getSelectQuery());
                assertEquals("createAthenaView", restored.getSerializedObjectName());
                assertTrue(restored.validate(new AthenaDatabase()).getErrorMessages().contains(reason));
            }
        }
    }

    @Test
    public void supportedViewsKeepTheStandardChangelogFormat() throws Exception {
        for (String extension : List.of("xml", "json", "yaml")) {
            CreateAthenaViewChange change = change();
            CreateViewChange restored = roundTrip(change, extension);
            assertEquals(CreateViewChange.class, restored.getClass());
            assertEquals(change.getSelectQuery(), restored.getSelectQuery());
            assertFalse(restored.validate(new AthenaDatabase()).hasErrors());
        }
    }

    private CreateViewChange roundTrip(CreateAthenaViewChange change, String extension) throws Exception {
        Path folder = temporaryFolder.newFolder().toPath();
        Path file = folder.resolve("changelog." + extension);
        try (var output = Files.newOutputStream(file)) {
            ChangeLogSerializerFactory.getInstance().getSerializer(extension).write(List.of(changeSet(change)), output);
        }
        try (var resources = new DirectoryResourceAccessor(folder)) {
            DatabaseChangeLog loaded = ChangeLogParserFactory.getInstance().getParser(file.getFileName().toString(), resources)
                    .parse(file.getFileName().toString(), new ChangeLogParameters(new AthenaDatabase()), resources);
            return (CreateViewChange) loaded.getChangeSets().get(0).getChanges().get(0);
        }
    }

    private static CreateAthenaViewChange change() {
        CreateAthenaViewChange change = new CreateAthenaViewChange();
        change.setSchemaName("target");
        change.setViewName("departments_view");
        change.setSelectQuery("SELECT id FROM target.departments");
        return change;
    }

    private static ChangeSet changeSet(CreateViewChange change) {
        ChangeSet changeSet = new ChangeSet("1", "test", false, false, "changelog", null, null, new DatabaseChangeLog());
        changeSet.addChange(change);
        return changeSet;
    }
}
