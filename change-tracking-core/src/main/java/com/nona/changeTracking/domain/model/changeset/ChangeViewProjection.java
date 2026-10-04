package com.nona.changeTracking.domain.model.changeset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The single conversion core of the two change views (ADR-004, US04 / A9).
 * <p>
 * The complete view ({@link #toAllChanges(List)}) assembles the containers bottom up: entering a
 * node with a non empty path reserves the place of its entry in the flat output, the local
 * representation of the node is assembled once its children returned theirs, and the reserved place
 * is back filled, so the flat list keeps the pre order sequence while every logical node is
 * converted a constant number of times instead of being rebuilt from every flat entry above it. The
 * leaf view ({@link #toLeafChanges(List)}) walks the same tree carrying the actual parent path and
 * the nearest collection field name, and calls the single build core only for leaves, without
 * building the container views it does not return.
 * <p>
 * Both entries share the metadata resolution and the five {@link Change} construction rules of
 * {@link #buildChange(ChangeNode, String, String, String, String, List)} and return read only lists.
 * This class is stateless: it holds no mutable session field, no conversion result cache and no
 * state across calls, so a repeated acquisition rebuilds the views on demand. Two output positions
 * may hold the same {@link Change} instance when their path, metadata and children are equal; the
 * uniqueness of instances is not part of the contract.
 * <p>
 * The class stays package private: it is an implementation detail of {@link ChangeSet}, not part
 * of the published model surface of this package.
 */
final class ChangeViewProjection {

    /**
     * Creates the stateless conversion core; the caller keeps a single shared instance.
     */
    ChangeViewProjection() {
    }

    /**
     * Projects the complete view: every container and every leaf of the trees, flattened in pre
     * order, with the container children kept as a nested tree of relative paths.
     *
     * @param changes the object changes to project, never null
     * @return the unmodifiable flat view holding every node with a non empty path exactly once
     * @throws NullPointerException if changes is null
     */
    public List<Change> toAllChanges(final List<ObjectChange> changes) {
        Objects.requireNonNull(changes, "changes");
        final List<Change> flatOutput = new ArrayList<>();
        for (final ObjectChange objectChange : changes) {
            project(objectChange.changeTree(), "", null, flatOutput);
        }
        return Collections.unmodifiableList(flatOutput);
    }

    /**
     * Projects the leaf view: the leaves of the trees only, flattened in pre order, with the actual
     * parent path and the nearest collection field name inherited from the containing nodes.
     *
     * @param changes the object changes to project, never null
     * @return the unmodifiable flat view holding every leaf with its full path
     * @throws NullPointerException if changes is null
     */
    public List<Change> toLeafChanges(final List<ObjectChange> changes) {
        Objects.requireNonNull(changes, "changes");
        final List<Change> leafOutput = new ArrayList<>();
        for (final ObjectChange objectChange : changes) {
            collectLeaves(objectChange.changeTree(), "", null, leafOutput);
        }
        return Collections.unmodifiableList(leafOutput);
    }

    /**
     * Projects one node occurrence of the complete view and returns the representation its parent
     * needs. A node with a non empty path reserves the place of its flat entry before its children
     * are projected and back fills that place with the assembled entry afterwards; a node with an
     * empty path reserves nothing, while its children are still projected, because the complete view
     * skips empty paths in the flat list and keeps them in the container children.
     *
     * @param node                         the change node occurrence to project
     * @param parentPath                   the full path of the containing node, empty for the root
     * @param inheritedCollectionFieldName the nearest collection field name of the containing node,
     *                                     null outside a collection
     * @param flatOutput                   the flat output the entry of this node is back filled into
     * @return the relative representation of this node occurrence for the containing node
     */
    private Change project(final ChangeNode node, final String parentPath,
                           final String inheritedCollectionFieldName, final List<Change> flatOutput) {
        final String fullPath = node.path();
        final String relativePath = toRelativePath(fullPath, parentPath);
        final boolean entersFlat = !fullPath.isEmpty();
        final int reserved = entersFlat ? flatOutput.size() : -1;
        if (entersFlat) {
            flatOutput.add(null);
        }

        final List<Change> children;
        if (node instanceof ContainerChangeNode container) {
            final List<Change> childRepresentations = new ArrayList<>(container.children().size());
            for (final ChangeNode child : container.children()) {
                childRepresentations.add(project(child, fullPath, inheritedCollectionFieldName, flatOutput));
            }
            children = Collections.unmodifiableList(childRepresentations);
        } else {
            children = null;
        }

        final Change relative = buildChange(node, relativePath, fullPath, parentPath,
                inheritedCollectionFieldName, children);
        if (entersFlat) {
            flatOutput.set(reserved, buildChange(node, fullPath, fullPath, "", null, children));
        }
        return relative;
    }

    /**
     * Collects the leaf view of one node occurrence: containers are traversed to collect their leaf
     * descendants, leaves are projected with the actual parent path and the nearest collection field
     * name. Empty path leaves are collected as well; the leaf view keeps them, the complete view
     * skips them.
     *
     * @param node                         the change node occurrence to collect from
     * @param parentPath                   the full path of the containing node, empty for the root
     * @param inheritedCollectionFieldName the nearest collection field name of the containing node,
     *                                     null outside a collection
     * @param leafOutput                   the leaf output of the current projection
     */
    private void collectLeaves(final ChangeNode node, final String parentPath,
                               final String inheritedCollectionFieldName, final List<Change> leafOutput) {
        final String fullPath = node.path();

        if (node instanceof ContainerChangeNode container) {
            final String relativePath = toRelativePath(fullPath, parentPath);
            final String collectionFieldName =
                    resolveCollectionFieldName(relativePath, parentPath, inheritedCollectionFieldName);
            for (final ChangeNode child : container.children()) {
                collectLeaves(child, fullPath, collectionFieldName, leafOutput);
            }
            return;
        }

        leafOutput.add(buildChange(node, fullPath, fullPath, parentPath, inheritedCollectionFieldName, null));
    }

    /**
     * Builds one {@link Change} from a node occurrence: this is the single construction core both
     * entries share, so the metadata of the five change types is resolved in one place.
     *
     * @param node                         the change node occurrence to convert
     * @param path                         the path carried by the produced change, the relative path in
     *                                     the container children, the full path in both flat views
     * @param fullPath                     the full path of the occurrence
     * @param parentPath                   the full path of the containing node, empty for the root
     * @param inheritedCollectionFieldName the nearest collection field name of the containing node,
     *                                     null outside a collection
     * @param children                     the relative representations of the children of a container
     *                                     node, null for a leaf node
     * @return the change holding the metadata, the payload and the children of the occurrence
     * @throws IllegalStateException if the node is not one of the five change node types
     */
    private Change buildChange(final ChangeNode node, final String path, final String fullPath,
                               final String parentPath, final String inheritedCollectionFieldName,
                               final List<Change> children) {
        final String relativePath = toRelativePath(fullPath, parentPath);
        final String fieldName = extractFieldName(relativePath);
        final boolean currentParentIsCollection = relativePath.startsWith("[");
        final String collectionFieldName =
                resolveCollectionFieldName(relativePath, parentPath, inheritedCollectionFieldName);

        if (node instanceof FieldChangeNode fcn) {
            return new ValueChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, fcn.oldValue(), fcn.newValue());
        }
        if (node instanceof ObjectFieldChangeNode ofcn) {
            return new ObjectFieldChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, ofcn.oldNode(), ofcn.newNode());
        }
        if (node instanceof ItemAddedNode ian) {
            return new ItemAddedChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, ian.addedItem());
        }
        if (node instanceof ItemRemovedNode irn) {
            return new ItemRemovedChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, irn.removedItem());
        }
        if (node instanceof ContainerChangeNode) {
            return new ContainerChange(path, fullPath, fieldName, collectionFieldName,
                    currentParentIsCollection, children);
        }
        throw new IllegalStateException("Unknown ChangeNode type: " + node.getClass());
    }

    /**
     * Resolves the path of a node relative to its containing node.
     *
     * @param fullPath   the full path of the node
     * @param parentPath the full path of the containing node, empty for the root
     * @return the relative path of the node
     */
    private static String toRelativePath(final String fullPath, final String parentPath) {
        if (parentPath.isEmpty()) {
            return fullPath;
        }
        if (fullPath.startsWith(parentPath + ".")) {
            return fullPath.substring(parentPath.length() + 1);
        }
        if (fullPath.startsWith(parentPath + "[")) {
            return fullPath.substring(parentPath.length());
        }
        return fullPath;
    }

    /**
     * Resolves the pure field name of a path: the field name without its index, null for a pure
     * index path.
     *
     * @param path the path to resolve
     * @return the pure field name, null when the path carries no field name
     */
    private static String extractFieldName(final String path) {
        if (path == null || path.isEmpty() || path.startsWith("[")) {
            return null;
        }
        final int lastDotIndex = path.lastIndexOf('.');
        final String lastSegment;
        if (lastDotIndex >= 0) {
            lastSegment = path.substring(lastDotIndex + 1);
        } else {
            lastSegment = path;
        }

        if (lastSegment.startsWith("[")) {
            return null;
        }

        final int bracketIndex = lastSegment.indexOf('[');
        if (bracketIndex > 0) {
            return lastSegment.substring(0, bracketIndex);
        }
        return lastSegment;
    }

    /**
     * Resolves the nearest collection field name of a node: a relative path starting with an index
     * resolves it from the path of the containing node, every other path inherits the context of its
     * containing node.
     *
     * @param relativePath                 the path of the node relative to its containing node
     * @param parentPath                   the full path of the containing node, empty for the root
     * @param inheritedCollectionFieldName the nearest collection field name of the containing node,
     *                                     null outside a collection
     * @return the nearest collection field name, null outside a collection
     */
    private static String resolveCollectionFieldName(final String relativePath, final String parentPath,
                                                     final String inheritedCollectionFieldName) {
        if (relativePath.startsWith("[")) {
            return extractFieldName(parentPath);
        }
        return inheritedCollectionFieldName;
    }
}
