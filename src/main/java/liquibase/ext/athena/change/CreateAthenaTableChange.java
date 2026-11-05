package liquibase.ext.athena.change;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import liquibase.change.ChangeMetaData;
import liquibase.change.DatabaseChange;
import liquibase.change.DatabaseChangeProperty;
import liquibase.change.core.CreateTableChange;
import liquibase.database.Database;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawParameterizedSqlStatement;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Table;

/** Preserves native storage and partition clauses while allowing target-schema remapping. */
@DatabaseChange(name = "createAthenaTable", description = "Creates an Athena table",
        priority = ChangeMetaData.PRIORITY_DEFAULT, appliesTo = "table")
public class CreateAthenaTableChange extends CreateTableChange {
    private static final String IDENTIFIER = "(?:`(?:``|[^`])+`|\"(?:\"\"|[^\"])+\"|[\\w$-]+)";
    private static final Pattern CREATE_TABLE_PREFIX = Pattern.compile(
            "^\\s*(CREATE\\s+(?:EXTERNAL\\s+)?TABLE\\s+)(IF\\s+NOT\\s+EXISTS\\s+)?"
                    + IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")*\\s*(?=\\()",
            Pattern.CASE_INSENSITIVE);

    private String tableDefinition;

    @DatabaseChangeProperty(description = "Complete source SHOW CREATE TABLE definition",
            requiredForDatabase = "athena", supportsDatabase = "athena")
    public String getTableDefinition() {
        return tableDefinition;
    }

    public void setTableDefinition(String tableDefinition) {
        this.tableDefinition = tableDefinition;
    }

    @Override
    public boolean supports(Database database) {
        return database instanceof AthenaDatabase;
    }

    @Override
    public SqlStatement[] generateStatements(Database database) {
        Matcher prefix = CREATE_TABLE_PREFIX.matcher(tableDefinition == null ? "" : tableDefinition);
        if (!prefix.find()) {
            throw new UnexpectedLiquibaseException("Cannot read Athena CREATE TABLE definition for " + getTableName());
        }
        String tableName = ((AthenaDatabase) database).escapeTableNameForDdl(getSchemaName(), getTableName());
        String sql = prefix.group(1)
                + (Boolean.TRUE.equals(getIfNotExists()) || prefix.group(2) != null ? "IF NOT EXISTS " : "")
                + tableName + " " + tableDefinition.substring(prefix.end());
        return new SqlStatement[] { new RawParameterizedSqlStatement(sql) };
    }

    @Override
    public Set<DatabaseObject> getAffectedDatabaseObjects(Database database) {
        return Set.of(new Table(getCatalogName(), getSchemaName(), getTableName()));
    }

    @Override
    public String getSerializedObjectNamespace() {
        return GENERIC_CHANGELOG_EXTENSION_NAMESPACE;
    }
}
