package liquibase.ext.athena.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import liquibase.CatalogAndSchema;
import liquibase.diff.compare.CompareControl;
import liquibase.exception.DatabaseException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NumericBind;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.SimpleNode;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Makes a view query read the schemas of the comparison database instead of the reference schemas it was written for.
 * Only relation names are rewritten: expressions, aliases, comments and literals keep their text.
 */
final class AthenaViewReferences {
    private static final long PARSE_TIMEOUT_MS = 1000;

    private AthenaViewReferences() {
    }

    /** A relation name with unquoted lower case parts. An unqualified name has an empty catalog and schema. */
    record Relation(String catalog, String schema, String name) {
        static Relation of(String catalog, String schema, String name, String defaultCatalog) {
            return new Relation(identifier(catalog == null ? defaultCatalog : catalog), identifier(schema), identifier(name));
        }
    }

    /** The reference schemas which are compared with another schema. The relation name of the entries is empty. */
    static Map<Relation, Relation> schemas(CompareControl.SchemaComparison[] comparisons, String referenceCatalog,
            String comparisonCatalog) {
        Map<Relation, Relation> schemas = new HashMap<>();
        if (comparisons == null) {
            return schemas;
        }
        for (CompareControl.SchemaComparison comparison : comparisons) {
            CatalogAndSchema reference = comparison.getReferenceSchema();
            CatalogAndSchema target = comparison.getComparisonSchema();
            if (reference == null || target == null || reference.getSchemaName() == null || target.getSchemaName() == null) {
                continue;
            }
            Relation from = Relation.of(reference.getCatalogName(), reference.getSchemaName(), null, referenceCatalog);
            Relation to = Relation.of(target.getCatalogName(), target.getSchemaName(), null, comparisonCatalog);
            if (!from.equals(to)) {
                schemas.put(from, to);
            }
        }
        return schemas;
    }

    /**
     * Rewrites the relations of the given schemas. Every relation which the query reads is added to {@code references}:
     * the key is the relation as written, the value is the relation which the result reads.
     */
    static String remap(String query, Map<Relation, Relation> schemas, String defaultCatalog,
            Map<Relation, Relation> references) throws DatabaseException {
        List<SimpleNode> nodes = parse(query);
        Map<Token, String> edits = new HashMap<>();
        Set<Relation> changedBindings = new HashSet<>();
        for (SimpleNode node : nodes) {
            if (!"TableName".equals(node.toString()) || !(node.jjtGetValue() instanceof Table table)
                    || table.getNameParts().size() > 3) {
                continue;
            }
            if (table.getSchemaName() == null) {
                Relation relation = new Relation("", "", identifier(table.getName()));
                if (!isCommonTableExpression(node, relation.name())) {
                    references.put(relation, relation);
                }
                continue;
            }
            Relation original = Relation.of(table.getDatabaseName(), table.getSchemaName(), table.getName(), defaultCatalog);
            Relation schema = schemas.get(new Relation(original.catalog(), original.schema(), ""));
            if (schema == null) {
                references.put(original, original);
                continue;
            }
            Relation target = new Relation(schema.catalog(), schema.schema(), original.name());
            references.put(original, target);
            List<Token> parts = tokens(node).stream().filter(token -> !".".equals(token.image)).toList();
            if (parts.size() != table.getNameParts().size()) {
                throw new DatabaseException("Cannot map Athena relation " + table + ". Use explicit relation aliases in the view.");
            }
            String schemaName = quote(target.schema());
            if (parts.size() == 3) {
                edits.put(parts.get(0), quote(target.catalog()));
            } else if (!original.catalog().equals(target.catalog())) {
                schemaName = quote(target.catalog()) + "." + schemaName;
            }
            edits.put(parts.get(parts.size() - 2), schemaName);
            if (table.getAlias() == null) {
                changedBindings.add(original);
                changedBindings.add(target);
            }
        }
        // Changing an unaliased relation also changes the namespaces visible to expressions.
        for (SimpleNode node : nodes) {
            Object value = node.jjtGetValue();
            Table qualifier = value instanceof Column column ? column.getTable()
                    : value instanceof AllTableColumns all ? all.getTable() : null;
            if (qualifier != null) {
                List<String> parts = new ArrayList<>(qualifier.getNameParts().stream().map(AthenaViewReferences::identifier).toList());
                Collections.reverse(parts);
                parts.add(value instanceof Column column ? identifier(column.getColumnName()) : "*");
                if (changedBindings.stream().anyMatch(relation -> matches(relation, parts))) {
                    throw new DatabaseException("Cannot safely map Athena qualified reference " + value
                            + ". Use explicit relation aliases in the view.");
                }
            }
        }
        StringBuilder result = new StringBuilder(query);
        edits.entrySet().stream().sorted(Comparator.comparingInt(edit -> -edit.getKey().absoluteBegin))
                .forEach(edit -> result.replace(edit.getKey().absoluteBegin - 1, edit.getKey().absoluteEnd - 1, edit.getValue()));
        return result.toString();
    }

    /** The tokens of the query without comments and letter case differences, to compare two definitions. */
    static List<String> canonical(String query) throws DatabaseException {
        return tokens(parse(query).get(0)).stream().filter(token -> !";".equals(token.image))
                .map(token -> token.kind == CCJSqlParserConstants.S_CHAR_LITERAL ? token.image : identifier(token.image))
                .toList();
    }

    // A common table expression hides relations with the same name only from the nodes which follow it.
    private static boolean isCommonTableExpression(SimpleNode table, String name) {
        for (Node node = table; node.jjtGetParent() != null; node = node.jjtGetParent()) {
            Node parent = node.jjtGetParent();
            for (int index = 0; parent.jjtGetChild(index) != node; index++) {
                SimpleNode sibling = (SimpleNode) parent.jjtGetChild(index);
                if ("WithItem".equals(sibling.toString())) {
                    Token alias = sibling.jjtGetFirstToken();
                    if (alias.kind == CCJSqlParserConstants.K_RECURSIVE) {
                        alias = alias.next;
                    }
                    if (name.equals(identifier(alias.image))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matches(Relation relation, List<String> parts) {
        return parts.size() >= 2 && parts.get(0).equals(relation.schema()) && parts.get(1).equals(relation.name())
                || parts.size() >= 3 && parts.get(0).equals(relation.catalog())
                        && parts.get(1).equals(relation.schema()) && parts.get(2).equals(relation.name());
    }

    private static List<SimpleNode> parse(String query) throws DatabaseException {
        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(query,
                    parser -> parser.withAllowComplexParsing(false).withTimeOut(PARSE_TIMEOUT_MS));
        } catch (JSQLParserException e) {
            throw new DatabaseException("Error parsing SQL query: " + e.getMessage(), e);
        }
        if (statements == null || statements.size() != 1 || !(statements.get(0) instanceof Select select)
                || select.getASTNode() == null) {
            throw new DatabaseException("Expected a SELECT view definition");
        }
        SimpleNode root = select.getASTNode();
        // The statement root also contains CTEs preceding the SELECT node.
        while (root.jjtGetParent() != null) {
            root = (SimpleNode) root.jjtGetParent();
        }
        List<SimpleNode> nodes = new ArrayList<>(List.of(root));
        for (int index = 0; index < nodes.size(); index++) {
            SimpleNode node = nodes.get(index);
            if (node.jjtGetValue() instanceof JdbcParameter || node.jjtGetValue() instanceof JdbcNamedParameter
                    || node.jjtGetValue() instanceof NumericBind) {
                throw new DatabaseException("Athena view definitions cannot contain parameters");
            }
            for (int child = 0; child < node.jjtGetNumChildren(); child++) {
                nodes.add((SimpleNode) node.jjtGetChild(child));
            }
        }
        return nodes;
    }

    private static List<Token> tokens(SimpleNode node) {
        List<Token> tokens = new ArrayList<>();
        for (Token token = node.jjtGetFirstToken(); token != null; token = token.next) {
            if (token.kind != CCJSqlParserConstants.EOF) {
                tokens.add(token);
            }
            if (token == node.jjtGetLastToken()) {
                break;
            }
        }
        return tokens;
    }

    private static String identifier(String name) {
        if (name == null) {
            return "";
        }
        if (name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1).replace("\"\"", "\"");
        } else if (name.startsWith("`") && name.endsWith("`")) {
            name = name.substring(1, name.length() - 1).replace("``", "`");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
