package liquibase.ext.athena.snapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.database.AthenaDatabase;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.SchemaSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.util.JdbcUtil;

/**
 * Restricts schema discovery to the active Athena data catalog.
 *
 * <p>Athena JDBC 3.7 returns schemas from every visible catalog from the
 * no-argument {@code DatabaseMetaData.getSchemas()} call. Liquibase's default
 * snapshot generator uses that call and can therefore match the same schema
 * name more than once. Schema Compare opens a connection for the selected
 * catalog, so querying that catalog explicitly produces an unambiguous
 * snapshot.</p>
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class AthenaSchemaSnapshotGenerator extends SchemaSnapshotGenerator {
    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (database instanceof AthenaDatabase) {
            int priority = super.getPriority(objectType, database);
            return priority == PRIORITY_NONE ? PRIORITY_NONE : priority + PRIORITY_DATABASE;
        }
        return PRIORITY_NONE;
    }

    @Override
    public Class<? extends SnapshotGenerator>[] replaces() {
        return new Class[] { SchemaSnapshotGenerator.class };
    }

    @Override
    protected String[] getDatabaseSchemaNames(Database database) throws SQLException, DatabaseException {
        List<String> schemaNames = new ArrayList<>();
        JdbcConnection connection = (JdbcConnection) database.getConnection();
        try (ResultSet schemas = connection.getMetaData().getSchemas(database.getDefaultCatalogName(), null)) {
            while (schemas.next()) {
                schemaNames.add(JdbcUtil.getValueForColumn(schemas, "TABLE_SCHEM", database));
            }
        }
        return schemaNames.toArray(String[]::new);
    }

    @Override
    protected CatalogAndSchema toCatalogAndSchema(String tableSchema, Database database) {
        return new CatalogAndSchema(database.getDefaultCatalogName(), tableSchema).customize(database);
    }
}
