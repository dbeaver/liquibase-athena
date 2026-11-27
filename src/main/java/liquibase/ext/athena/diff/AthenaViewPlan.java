package liquibase.ext.athena.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import liquibase.change.Change;
import liquibase.change.core.CreateViewChange;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.Database;
import liquibase.diff.DiffResult;
import liquibase.diff.Difference;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.ObjectChangeFilter;
import liquibase.diff.output.changelog.core.ChangedViewChangeGenerator;
import liquibase.diff.output.changelog.core.MissingViewChangeGenerator;
import liquibase.exception.DatabaseException;
import liquibase.ext.athena.change.CreateAthenaViewChange;
import liquibase.ext.athena.diff.AthenaViewReferences.Relation;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.structure.core.Table;
import liquibase.structure.core.View;

/**
 * The changes for all views of one comparison. They are generated together, because a view has to be created after
 * the views it reads, and a view built on a view which cannot be created cannot be created either.
 */
final class AthenaViewPlan {
    private static final Comparator<View> BY_NAME = Comparator.comparing(view -> view.getName().toLowerCase(Locale.ROOT));
    // A comparison marks the objects it has handled in its control, so the plan lives as long as that control.
    private static final Map<DiffOutputControl, AthenaViewPlan> PLANS = Collections.synchronizedMap(new WeakHashMap<>());

    private final List<View> missingViews = new ArrayList<>();
    private final List<View> changedViews = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();
    private final boolean replacesViews;

    /** The plan of the running comparison, or of this view alone when it is generated outside a comparison. */
    static AthenaViewPlan of(View view, ObjectDifferences differences, DiffOutputControl control, Database referenceDatabase,
            Database comparisonDatabase) {
        // Liquibase keeps the result of the running comparison in the comparison database for change generators.
        Object result = comparisonDatabase instanceof AbstractJdbcDatabase database ? database.get("diffResult") : null;
        if (result instanceof DiffResult comparison) {
            return PLANS.computeIfAbsent(control, key -> new AthenaViewPlan(comparison.getMissingObjects(View.class),
                    comparison.getChangedObjects(View.class), comparison.getReferenceSnapshot(),
                    comparison.getComparisonSnapshot(), control, referenceDatabase, comparisonDatabase));
        }
        return new AthenaViewPlan(differences == null ? Set.of(view) : Set.of(),
                differences == null ? Map.of() : Map.of(view, differences), null, null, control, referenceDatabase,
                comparisonDatabase);
    }

    private AthenaViewPlan(Set<View> missing, Map<View, ObjectDifferences> changed, DatabaseSnapshot referenceSnapshot,
            DatabaseSnapshot comparisonSnapshot, DiffOutputControl control, Database referenceDatabase,
            Database comparisonDatabase) {
        String referenceCatalog = referenceDatabase.getDefaultCatalogName();
        Map<Relation, Relation> schemas = AthenaViewReferences.schemas(control.getSchemaComparisons(), referenceCatalog,
                comparisonDatabase.getDefaultCatalogName());
        MissingViewChangeGenerator missingGenerator = new MissingViewChangeGenerator() {
            @Override
            protected CreateViewChange createViewChange() {
                return new CreateAthenaViewChange();
            }
        };
        ChangedViewChangeGenerator changedGenerator = new ChangedViewChangeGenerator() {
            @Override
            protected CreateViewChange createViewChange() {
                return new CreateAthenaViewChange();
            }
        };
        ObjectChangeFilter filter = control.getObjectChangeFilter();
        Map<View, Change[]> generated = new IdentityHashMap<>();
        for (View view : missing.stream().sorted(BY_NAME).toList()) {
            if (filter == null || filter.includeMissing(view, referenceDatabase, comparisonDatabase)) {
                missingViews.add(view);
                generated.put(view, missingGenerator.fixMissing(view, control, referenceDatabase, comparisonDatabase, null));
            }
        }
        for (View view : changed.keySet().stream().sorted(BY_NAME).toList()) {
            ObjectDifferences differences = changed.get(view);
            if ((filter == null || filter.includeChanged(view, differences, referenceDatabase, comparisonDatabase))
                    && !differsOnlyBySchemaNames(differences, schemas, referenceCatalog, referenceSnapshot, comparisonSnapshot)) {
                changedViews.add(view);
                generated.put(view, changedGenerator.fixChanged(view, differences, control, referenceDatabase,
                        comparisonDatabase, null));
            }
        }

        List<View> views = Stream.concat(missingViews.stream(), changedViews.stream()).collect(Collectors.toList());
        Map<View, CreateAthenaViewChange> creations = new IdentityHashMap<>();
        Map<Relation, View> created = new HashMap<>();
        Map<View, Set<Relation>> reads = new IdentityHashMap<>();
        for (View view : views) {
            CreateAthenaViewChange creation = Stream.of(generated.get(view))
                    .filter(CreateAthenaViewChange.class::isInstance).map(CreateAthenaViewChange.class::cast).findFirst().orElseThrow();
            creations.put(view, creation);
            Relation source = Relation.of(view.getSchema() == null ? null : view.getSchema().getCatalogName(),
                    view.getSchema() == null ? null : view.getSchema().getName(), view.getName(), referenceCatalog);
            Relation schema = schemas.get(new Relation(source.catalog(), source.schema(), ""));
            Relation name = schema == null ? source : new Relation(schema.catalog(), schema.schema(), source.name());
            created.put(name, view);
            Map<Relation, Relation> references = new HashMap<>();
            try {
                creation.setSelectQuery(AthenaViewReferences.remap(creation.getSelectQuery(), schemas, referenceCatalog, references));
            } catch (DatabaseException | IllegalArgumentException e) {
                // An unparsed query may still depend on other views through unqualified names.
                creation.rejectReferences("Cannot map Athena view " + view.getName() + " to the target schema: " + e.getMessage());
            }
            reads.put(view, references.values().stream().map(dependency -> dependency.schema().isEmpty()
                    ? new Relation(name.catalog(), name.schema(), dependency.name()) : dependency).collect(Collectors.toSet()));
        }
        Map<View, Set<View>> prerequisites = new IdentityHashMap<>();
        for (View view : views) {
            Set<View> required = Collections.newSetFromMap(new IdentityHashMap<>());
            reads.get(view).stream().map(created::get).filter(Objects::nonNull).forEach(required::add);
            // A view which cannot be created blocks the views built on it like a cycle does.
            if (creations.get(view).getReferenceError() != null) {
                required.add(view);
            }
            prerequisites.put(view, required);
        }
        for (View view : order(views, prerequisites)) {
            if (creations.get(view).getReferenceError() == null) {
                creations.get(view).rejectReferences("Cannot create Athena view " + view.getName()
                        + ": it depends on itself or on a view which cannot be migrated");
            }
        }
        views.forEach(view -> changes.addAll(List.of(generated.get(view))));
        replacesViews = !changedViews.isEmpty();
    }

    /** True when an existing view is replaced: a new view may read it, so new views are generated with it. */
    boolean replacesViews() {
        return replacesViews;
    }

    /** All changes in the order to apply them when asked for the first time, nothing afterwards. */
    synchronized Change[] takeChanges(DiffOutputControl control) {
        if (changes.isEmpty()) {
            return null;
        }
        missingViews.forEach(control::setAlreadyHandledMissing);
        changedViews.forEach(control::setAlreadyHandledChanged);
        Change[] taken = changes.toArray(new Change[0]);
        // The plan stays cached until its comparison is gone, so it lets go of the snapshot objects.
        missingViews.clear();
        changedViews.clear();
        changes.clear();
        return taken;
    }

    // Puts every view after the views it requires. Returns the views left in their original order
    // because their requirements are cyclic.
    private static List<View> order(List<View> views, Map<View, Set<View>> prerequisites) {
        Set<View> pending = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.addAll(views);
        List<View> ordered = new ArrayList<>();
        while (!pending.isEmpty()) {
            View next = views.stream().filter(pending::contains)
                    .filter(view -> prerequisites.get(view).stream().noneMatch(pending::contains)).findFirst().orElse(null);
            if (next == null) {
                break;
            }
            ordered.add(next);
            pending.remove(next);
        }
        List<View> cyclic = views.stream().filter(pending::contains).toList();
        ordered.addAll(cyclic);
        views.clear();
        views.addAll(ordered);
        return cyclic;
    }

    // A view which differs from the reference one only by the names of the compared schemas is not changed.
    private static boolean differsOnlyBySchemaNames(ObjectDifferences differences, Map<Relation, Relation> schemas,
            String referenceCatalog, DatabaseSnapshot referenceSnapshot, DatabaseSnapshot comparisonSnapshot) {
        Difference definition = differences.getDifference("definition");
        if (referenceSnapshot == null || comparisonSnapshot == null || definition == null
                || differences.getDifferences().size() != 1
                || !(definition.getReferenceValue() instanceof String reference)
                || !(definition.getComparedValue() instanceof String compared)) {
            return false;
        }
        try {
            Map<Relation, Relation> references = new HashMap<>();
            String mapped = AthenaViewReferences.remap(reference, schemas, referenceCatalog, references);
            Map<Relation, Set<String>> referenceColumns = columns(referenceSnapshot);
            Map<Relation, Set<String>> comparisonColumns = columns(comparisonSnapshot);
            // Identical SQL can bind differently when the available columns differ.
            return references.entrySet().stream().allMatch(read -> {
                Set<String> columns = referenceColumns.get(read.getKey());
                return read.getKey().equals(read.getValue()) || columns != null && !columns.isEmpty()
                        && columns.equals(comparisonColumns.get(read.getValue()));
            })
                    && !mapped.equals(reference)
                    && AthenaViewReferences.canonical(mapped).equals(AthenaViewReferences.canonical(compared));
        } catch (DatabaseException | IllegalArgumentException e) {
            // The definitions cannot be proven equal, so the view stays changed.
            return false;
        }
    }

    private static Map<Relation, Set<String>> columns(DatabaseSnapshot snapshot) {
        Map<Relation, Set<String>> columns = new HashMap<>();
        String catalog = snapshot.getDatabase().getDefaultCatalogName();
        Stream.concat(snapshot.get(Table.class).stream(), snapshot.get(View.class).stream())
                .filter(relation -> relation.getSchema() != null)
                .forEach(relation -> columns.put(
                        Relation.of(relation.getSchema().getCatalogName(), relation.getSchema().getName(), relation.getName(), catalog),
                        relation.getColumns().stream().map(column -> column.getName().toLowerCase(Locale.ROOT))
                                .collect(Collectors.toSet())));
        return columns;
    }
}
