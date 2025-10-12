package com.nona.changeTracking.domain.detector;

import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.unitofwork.Snapshot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ChangeDetector {
    private final Map<Class<? extends Snapshot<?>>, SnapshotComparator<?>> comparators;

    public ChangeDetector(final Map<Class<? extends Snapshot<?>>, SnapshotComparator<?>> comparators) {
        this.comparators = Map.copyOf(comparators);
    }

    public Optional<ObjectChange> detectChanges(final Object originalObject, final Snapshot<?> snapshot) {
        final SnapshotComparator<Snapshot<?>> comparator = findComparatorFor(snapshot);
        final List<FieldChange> fieldChanges = comparator.compare(snapshot, originalObject);

        if (fieldChanges.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(new ObjectChange(originalObject, fieldChanges));
        }
    }

    @SuppressWarnings("unchecked")
    private SnapshotComparator<Snapshot<?>> findComparatorFor(final Snapshot<?> snapshot) {
        final SnapshotComparator<?> comparator = comparators.get(snapshot.getClass());
        if (comparator == null) {
            throw new UnsupportedSnapshotTypeException(snapshot.getClass());
        }
        return (SnapshotComparator<Snapshot<?>>) comparator;
    }
}
