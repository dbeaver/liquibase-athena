package liquibase.ext.athena.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import liquibase.Scope;
import liquibase.change.ChangeFactory;
import liquibase.change.ColumnConfig;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.H2Database;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.parser.core.xml.XMLChangeLogSAXParser;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.serializer.core.xml.XMLChangeLogSerializer;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestCreateAthenaTableChange {
    private static final String BODY = "(`id` bigint COMMENT 'source.events (id)', `data` struct<name:string>)\n"
            + "PARTITIONED BY (`day` string)\n"
            + "ROW FORMAT SERDE 'org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe'\n"
            + "STORED AS INPUTFORMAT 'org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat'\n"
            + "OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat'\n"
            + "LOCATION 's3://source-bucket/source.events/'\n"
            + "TBLPROPERTIES ('classification'='parquet')";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preservesNativeDefinitionAndRemapsOnlyTheCreatedTable() {
        CreateAthenaTableChange change = change("CREATE EXTERNAL TABLE `source`.`events` " + BODY);

        assertEquals("CREATE EXTERNAL TABLE `target`.`events` " + BODY, sql(change));
        assertFalse(change.validate(new AthenaDatabase()).getErrorMessages().toString(),
                change.validate(new AthenaDatabase()).hasErrors());
        assertFalse(change.warn(new AthenaDatabase()).hasWarnings());
        assertTrue(change.supports(new AthenaDatabase()));
        assertFalse(change.supports(new H2Database()));
    }

    @Test
    public void handlesQuotedIdentifiersContainingSpacesDotsAndParentheses() {
        CreateAthenaTableChange change = change(
                "CREATE EXTERNAL TABLE `source db`.`events.(old)``name` " + BODY);
        change.setTableName("Events`New");
        change.setSchemaName("Target Db");

        assertEquals("CREATE EXTERNAL TABLE `target db`.`events``new` " + BODY, sql(change));
    }

    @Test
    public void preservesNativeIcebergSyntaxAndProperties() {
        String body = "(id bigint) LOCATION 's3://bucket/iceberg/' TBLPROPERTIES ('table_type'='ICEBERG')";
        CreateAthenaTableChange change = change("CREATE TABLE source.events " + body);

        assertEquals("CREATE TABLE `target`.`events` " + body, sql(change));
    }

    @Test
    public void usesDefaultSchemaAndRetainsIfNotExists() {
        CreateAthenaTableChange change = change("CREATE EXTERNAL TABLE IF NOT EXISTS events " + BODY);
        change.setSchemaName(null);
        AthenaDatabase database = new AthenaDatabase();
        database.setDefaultSchemaName("Default_Db");
        database.setDefaultCatalogName("AwsDataCatalog");

        assertEquals("CREATE EXTERNAL TABLE IF NOT EXISTS `default_db`.`events` " + BODY,
                SqlGeneratorFactory.getInstance().generateSql(change.generateStatements(database)[0], database)[0].toSql());
    }

    @Test
    public void rejectsMissingOrUnreadableDefinitions() {
        for (String definition : new String[] { null, "", "[CANNOT READ TABLE DEFINITION]", "SELECT * FROM events" }) {
            assertThrows(UnexpectedLiquibaseException.class, () -> sql(change(definition)));
        }
    }

    @Test
    public void nativeDefinitionSurvivesXmlChangelogRoundTrip() throws Exception {
        CreateAthenaTableChange change = change("CREATE EXTERNAL TABLE source.events " + BODY);
        ChangeSet changeSet = new ChangeSet("1", "test", false, false, "changelog.xml", null, null,
                new DatabaseChangeLog());
        changeSet.addChange(change);
        Path file = temporaryFolder.newFile("changelog.xml").toPath();
        try (var output = Files.newOutputStream(file)) {
            new XMLChangeLogSerializer().write(List.of(changeSet), output);
        }
        assertTrue(Scope.getCurrentScope().getSingleton(ChangeFactory.class).create("createAthenaTable")
                instanceof CreateAthenaTableChange);
        try (var resources = new DirectoryResourceAccessor(file.getParent())) {
            DatabaseChangeLog loaded = new XMLChangeLogSAXParser().parse("changelog.xml",
                    new ChangeLogParameters(new AthenaDatabase()), resources);
            CreateAthenaTableChange restored = (CreateAthenaTableChange) loaded.getChangeSets().get(0).getChanges().get(0);
            assertEquals(change.getTableDefinition(), restored.getTableDefinition());
            assertEquals(sql(change), sql(restored));
        }
    }

    private static CreateAthenaTableChange change(String definition) {
        CreateAthenaTableChange change = new CreateAthenaTableChange();
        change.setCatalogName("AwsDataCatalog");
        change.setSchemaName("target");
        change.setTableName("events");
        change.addColumn(new ColumnConfig().setName("id").setType("BIGINT"));
        change.setTableDefinition(definition);
        return change;
    }

    private static String sql(CreateAthenaTableChange change) {
        AthenaDatabase database = new AthenaDatabase();
        return SqlGeneratorFactory.getInstance().generateSql(change.generateStatements(database)[0], database)[0].toSql();
    }
}
