package liquibase.ext.athena.change;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import liquibase.change.core.CreateViewChange;
import liquibase.database.Database;
import liquibase.diff.compare.CompareControl;
import liquibase.exception.DatabaseException;
import liquibase.exception.ValidationErrors;
import liquibase.ext.athena.database.AthenaViewReferences;
import liquibase.ext.athena.database.AthenaViewReferences.Relation;

/**
 * A view generated from a view of another schema. Validation reports a query which still reads the schema it was
 * copied from, because it could not be rewritten safely.
 */
public class CreateAthenaViewChange extends CreateViewChange {
    private static final Pattern QUERY_START = Pattern.compile("\\s*(\\(|(select|with|values)\\b)");

    private String referenceError;

    /** The reason why the query could not be made to read the comparison schemas, or null. */
    public String getReferenceError() {
        return referenceError;
    }

    /** Makes the query read the comparison schemas instead of the reference schemas. */
    public void mapReferences(CompareControl.SchemaComparison[] comparisons, String referenceCatalog,
            String comparisonCatalog) {
        Map<Relation, Relation> schemas = AthenaViewReferences.schemas(comparisons, referenceCatalog, comparisonCatalog);
        if (schemas.isEmpty() || getSelectQuery() == null) {
            return;
        }
        try {
            setSelectQuery(AthenaViewReferences.remap(getSelectQuery(), schemas, referenceCatalog, new HashMap<>()));
        } catch (DatabaseException | IllegalArgumentException e) {
            if (!canKeepUnmapped(getSelectQuery(), schemas)) {
                referenceError = "Cannot map Athena view " + getViewName() + " to the target schema: " + e.getMessage();
            }
        }
    }

    @Override
    public ValidationErrors validate(Database database) {
        ValidationErrors errors = super.validate(database);
        if (referenceError != null) {
            errors.addError(referenceError);
        }
        return errors;
    }

    // Placeholders such as [CANNOT READ VIEW DEFINITION] are not queries,
    // and a query without the name of a mapped schema cannot read that schema.
    private static boolean canKeepUnmapped(String query, Map<Relation, Relation> schemas) {
        String text = query.toLowerCase(Locale.ROOT);
        return QUERY_START.matcher(text).lookingAt()
                && schemas.keySet().stream().noneMatch(schema -> text.contains(schema.schema()));
    }
}
