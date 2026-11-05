package liquibase.ext.athena.snapshot;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import liquibase.database.AbstractJdbcDatabase;
import liquibase.exception.DatabaseException;
import liquibase.snapshot.CachedRow;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.JdbcDatabaseSnapshot;
import liquibase.structure.core.Schema;

/** Reads both standard JDBC and legacy Simba/Glue relation types once per schema snapshot. */
final class AthenaRelationMetadata {
    private static final String CACHE_KEY = AthenaRelationMetadata.class.getName();
    private static final Set<String> TABLE_TYPES = Set.of("TABLE", "EXTERNAL_TABLE", "MANAGED_TABLE", "EXTERNAL");
    private static final Set<String> VIEW_TYPES = Set.of("VIEW", "VIRTUAL_VIEW");

    private AthenaRelationMetadata() {
    }

    static List<CachedRow> getRelations(DatabaseSnapshot snapshot, Schema schema, boolean views) throws DatabaseException {
        AbstractJdbcDatabase database = (AbstractJdbcDatabase) snapshot.getDatabase();
        String catalog = database.getJdbcCatalogName(schema);
        String schemaName = database.getJdbcSchemaName(schema);
        @SuppressWarnings("unchecked")
        Map<List<String>, List<CachedRow>> cache = (Map<List<String>, List<CachedRow>>) snapshot.getScratchData(CACHE_KEY);
        if (cache == null) {
            cache = new HashMap<>();
            snapshot.setScratchData(CACHE_KEY, cache);
        }
        List<String> key = Arrays.asList(catalog, schemaName);
        List<CachedRow> relations = cache.get(key);
        if (relations == null) {
            relations = new ArrayList<>();
            try {
                DatabaseMetaData metadata = ((JdbcDatabaseSnapshot) snapshot).getMetaDataFromCache().getDatabaseMetaData();
                // Simba returns Glue's EXTERNAL_TABLE/VIRTUAL_VIEW types unless explicitly configured otherwise.
                // Request all types, then classify them without changing connection-wide driver settings.
                try (ResultSet rows = metadata.getTables(catalog, escapePattern(schemaName, metadata), "%", null)) {
                    while (rows.next()) {
                        Map<String, Object> values = new HashMap<>();
                        for (String column : List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS")) {
                            values.put(column, rows.getString(column));
                        }
                        relations.add(new CachedRow(values));
                    }
                }
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }
            cache.put(key, relations);
        }
        Set<String> types = views ? VIEW_TYPES : TABLE_TYPES;
        return relations.stream().filter(row -> {
            String type = row.getString("TABLE_TYPE");
            return type != null && types.contains(type.toUpperCase(Locale.ROOT));
        }).toList();
    }

    private static String escapePattern(String name, DatabaseMetaData metadata) throws SQLException {
        String escape = metadata.getSearchStringEscape();
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
}
