package liquibase.ext.athena.database;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import liquibase.CatalogAndSchema;
import liquibase.Scope;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.DatabaseConnection;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.executor.ExecutorService;
import liquibase.ext.athena.configuration.AthenaConfiguration;
import liquibase.statement.core.RawParameterizedSqlStatement;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Relation;
import liquibase.structure.core.Sequence;
import liquibase.structure.core.UniqueConstraint;

public class AthenaDatabase extends AbstractJdbcDatabase {
    private static final int HIGH_PRIORITY = 10;

    private static final String NAME = "athena";
    private static final String PRODUCT_NAME = "AWS.Athena";

    private static final Set<String> JDBC_PREFIXES = Set.of("jdbc:athena:", "jdbc:awsathena:");
    private static final Set<String> PRODUCT_NAMES = Set.of("aws.athena", "amazon athena", "athena");
    private static final String DEFAULT_JDBC_DRIVER = "com.amazon.athena.jdbc.AthenaDriver";
    private static final int DEFAULT_JDBC_PORT = 443;

    private static final Set<String> RESERVED_WORDS = Set.of(
            "ALL", "ALTER", "AND", "ARRAY", "AS", "AUTHORIZATION", "BETWEEN", "BIGINT", "BINARY", "BOOLEAN",
            "BOTH", "BY", "CASE", "CASHE", "CAST", "CHAR", "COLUMN", "COMMIT", "CONF", "CONSTRAINT", "CREATE",
            "CROSS", "CUBE", "CURRENT", "CURRENT_DATE", "CURRENT_PATH", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_USER", "CURSOR", "DATABASE", "DATE", "DAYOFWEEK", "DEALLOCATE", "DECIMAL", "DELETE",
            "DESCRIBE", "DISTINCT", "DOUBLE", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT", "EXCHANGE", "EXECUTE",
            "EXISTS", "EXTENDED", "EXTERNAL", "EXTRACT", "FALSE", "FETCH", "FIRST", "FLOAT", "FLOOR", "FOLLOWING",
            "FOR", "FOREIGN", "FROM", "FULL", "FUNCTION", "GRANT", "GROUP", "GROUPING", "HAVING", "IF", "IMPORT",
            "IN", "INNER", "INSERT", "INT", "INTEGER", "INTERSECT", "INTERVAL", "INTO", "IS", "JOIN", "LAST",
            "LATERAL", "LEFT", "LESS", "LIKE", "LOCAL", "LOCALTIME", "LOCALTIMESTAMP", "MACRO", "MAP", "MORE",
            "NATURAL", "NONE", "NORMALIZE", "NOT", "NULL", "NUMERIC", "OF", "ON", "ONLY", "OR", "ORDER", "OUT",
            "OUTER", "OVER", "PARTIALSCAN", "PARTITION", "PERCENT", "PRECEDING", "PRECISION", "PREPARE", "PRESERVE",
            "PRIMARY", "PROCEDURE", "RANGE", "READS", "RECURSIVE", "REDUCE", "REFERENCES", "REGEXP", "REVOKE",
            "RIGHT", "RLIKE", "ROLLBACK", "ROLLUP", "ROW", "ROWS", "SELECT", "SET", "SKIP", "SMALLINT", "START",
            "TABLE", "TABLESAMPLE", "THEN", "TIME", "TIMESTAMP", "TO", "TRANSFORM", "TRIGGER", "TRUE", "TRUNCATE",
            "UNBOUNDED", "UNESCAPE", "UNION", "UNIQUEJOIN", "UNNEST", "UPDATE", "USER", "USING", "UTC_TIMESTAMP",
            "VALUES", "VARCHAR", "VIEWS", "WHEN", "WHERE", "WINDOW", "WITH");

    public AthenaDatabase() {
        setCurrentDateTimeFunction("CURRENT_TIMESTAMP");
        unquotedObjectsAreUppercased = false;
        addReservedWords(RESERVED_WORDS);
    }

    @Override
    public boolean isCorrectDatabaseImplementation(DatabaseConnection conn) throws DatabaseException {
        String url = conn.getURL();
        if (isAthenaUrl(url)) {
            return true;
        }
        String productName = conn.getDatabaseProductName();
        return productName != null && PRODUCT_NAMES.contains(productName.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String getDefaultDriver(String url) {
        if (isAthenaUrl(url)) {
            return DEFAULT_JDBC_DRIVER;
        }
        return null;
    }

    @Override
    public Integer getDefaultPort() {
        return DEFAULT_JDBC_PORT;
    }

    @Override
    public String getShortName() {
        return NAME;
    }

    @Override
    protected String getDefaultDatabaseProductName() {
        return PRODUCT_NAME;
    }

    @Override
    public int getPriority() {
        return HIGH_PRIORITY;
    }

    @Override
    public CatalogAndSchema.CatalogAndSchemaCase getSchemaAndCatalogCase() {
        return CatalogAndSchema.CatalogAndSchemaCase.ORIGINAL_CASE;
    }

    @Override
    public String escapeObjectName(String objectName, final Class<? extends DatabaseObject> objectType) {
        if (objectName != null) {
            // Athena recommend object names in lowercase. Athena will lowercase the name
            // for you to run the query.
            objectName = objectName.trim().toLowerCase();
            if (mustQuoteObjectName(objectName, objectType)) {
                return quoteObject(objectName, objectType);
            } else if (quotingStrategy == ObjectQuotingStrategy.QUOTE_ALL_OBJECTS) {
                return quoteObject(objectName, objectType);
            }
        }
        return objectName;
    }

    @Override
    public boolean requiresPassword() {
        return false;
    }

    @Override
    public boolean requiresUsername() {
        return true;
    }

    @Override
    public boolean isCaseSensitive() {
        return false;
    }

    @Override
    public boolean isAutoCommit() throws DatabaseException {
        return true;
    }

    @Override
    public void setAutoCommit(boolean b) {
    }

    @Override
    public boolean supportsInitiallyDeferrableColumns() {
        return false;
    }

    @Override
    public boolean supportsTablespaces() {
        return false;
    }

    @Override
    public boolean supportsSequences() {
        return false;
    }

    @Override
    public boolean supportsSchemas() {
        return true;
    }

    @Override
    public boolean supports(Class<? extends DatabaseObject> object) {
        if (Sequence.class.isAssignableFrom(object)
                || PrimaryKey.class.isAssignableFrom(object)
                || ForeignKey.class.isAssignableFrom(object)
                || UniqueConstraint.class.isAssignableFrom(object)
                || Index.class.isAssignableFrom(object)) {
            return false;
        }
        return super.supports(object);
    }

    @Override
    public boolean supportsCatalogInObjectName(Class<? extends DatabaseObject> type) {
        return Relation.class.isAssignableFrom(type);
    }

    @Override
    public boolean supportsDatabaseChangeLogHistory() {
        return true;
    }

    @Override
    public boolean supportsAutoIncrement() {
        return false;
    }

    @Override
    public boolean supportsRestrictForeignKeys() {
        return false;
    }

    @Override
    public boolean supportsDropTableCascadeConstraints() {
        return false;
    }

    @Override
    public boolean supportsDDLInTransaction() {
        return false;
    }

    @Override
    public boolean supportsPrimaryKeyNames() {
        return false;
    }

    @Override
    public String getViewDefinition(CatalogAndSchema schema, String viewName) throws DatabaseException {
        CatalogAndSchema customizedSchema = schema.customize(this);
        String qualifiedViewName = escapeViewName(
                customizedSchema.getCatalogName(), customizedSchema.getSchemaName(), viewName);
        @SuppressWarnings("unchecked")
        List<String> definitionLines = Scope.getCurrentScope().getSingleton(ExecutorService.class)
                .getExecutor("jdbc", this)
                .queryForList(new RawParameterizedSqlStatement("SHOW CREATE VIEW " + qualifiedViewName), String.class);
        return formatViewDefinition(definitionLines);
    }

    static String formatViewDefinition(List<String> definitionLines) {
        if (definitionLines == null || definitionLines.isEmpty()) {
            return null;
        }
        // Athena returns the CREATE VIEW statement one line per result row.
        return "FULL_DEFINITION: " + String.join("\n", definitionLines);
    }

    /*********************************
     * Custom code
     *********************************/

    public String getLiquibaseTableS3Location() {
        return AthenaConfiguration.LIQUIBASE_TABLE_S3_LOCATION.getCurrentValue();
    }

    public String getCreateTableStatement(String tableName, String columns) {
        String fullTableName = escapeTableName(getLiquibaseCatalogName(), getLiquibaseSchemaName(), tableName);

        String rootLocation = getLiquibaseTableS3Location();
        if (rootLocation == null || rootLocation.isBlank()) {
            throw new IllegalStateException("liquibase.athena.liquibaseTableS3Location must be configured");
        }
        if (rootLocation.endsWith("/")) {
            rootLocation = rootLocation.substring(0, rootLocation.length() - 1);
        }
        String location = String.format("%s/%s", rootLocation, tableName);

        return String.format(
                "CREATE TABLE IF NOT EXISTS %s (%s) LOCATION '%s' TBLPROPERTIES ( 'table_type' = 'ICEBERG')",
                fullTableName, columns, location);
    }

    @Override
    protected String getConnectionSchemaName() {
        if (getConnection() instanceof JdbcConnection jdbcConnection) {
            try {
                String schema = jdbcConnection.getUnderlyingConnection().getSchema();
                if (schema != null && !schema.isBlank()) {
                    return schema;
                }
            } catch (SQLException | AbstractMethodError | UnsupportedOperationException ignored) {
                // Older Athena JDBC drivers don't implement Connection.getSchema().
            }
        }
        return getUrlProperty("Database");
    }

    @Override
    protected String getConnectionCatalogName() throws DatabaseException {
        String urlCatalog = getUrlProperty("Catalog");
        try {
            String catalog = super.getConnectionCatalogName();
            return catalog == null || catalog.isBlank() ? urlCatalog : catalog;
        } catch (DatabaseException e) {
            if (urlCatalog == null || urlCatalog.isBlank()) {
                throw e;
            }
            return urlCatalog;
        } catch (AbstractMethodError | UnsupportedOperationException ignored) {
            return urlCatalog;
        }
    }

    private String getUrlProperty(String propertyName) {
        if (getConnection() == null) {
            return null;
        }
        String url = getConnection().getURL();
        if (url == null) {
            return null;
        }
        for (String part : url.split(";")) {
            int separator = part.indexOf('=');
            if (separator >= 0 && propertyName.equalsIgnoreCase(part.substring(0, separator).trim())) {
                return part.substring(separator + 1).trim();
            }
        }
        return null;
    }

    private static boolean isAthenaUrl(String url) {
        if (url == null) {
            return false;
        }
        String normalizedUrl = url.toLowerCase(Locale.ROOT);
        return JDBC_PREFIXES.stream().anyMatch(normalizedUrl::startsWith);
    }
}
