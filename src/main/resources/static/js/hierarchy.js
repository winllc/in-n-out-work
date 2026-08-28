/*
 * HierarchyTree
 *
 * Collapsible, filterable tree behaviour shared by the Groups page and the Org Chart.
 * Both render the same markup (fragments/grouptree.html and fragments/orgtree.html):
 *
 *   .group-node                       one node of the tree
 *     .group-item[data-group-name]    the row; data-group-name is the lowercased search text
 *       .group-toggle                 collapse button, absent on leaves
 *     .group-children.collapse        nested .group-node elements, absent on leaves
 *
 * Visibility is expressed by toggling `d-none` on .group-node, which is what app.css
 * expects. Expansion toggles the `show` class on .group-children and keeps the toggle
 * button's `collapsed` class in sync so the chevron points the right way. Bootstrap's
 * Collapse decides shown/hidden from that same `show` class, so the two coexist and a
 * branch opened here still closes on the next chevron click.
 */
(function (global) {
    "use strict";

    function childRow(node) {
        return node.querySelector(":scope > .group-item");
    }

    function childContainer(node) {
        return node.querySelector(":scope > .group-children");
    }

    function childNodes(node) {
        var container = childContainer(node);
        return container ? Array.prototype.slice.call(
            container.querySelectorAll(":scope > .group-node")) : [];
    }

    function setExpanded(node, expanded) {
        var container = childContainer(node);
        if (!container) {
            return;
        }
        container.classList.toggle("show", expanded);

        var row = childRow(node);
        var toggle = row ? row.querySelector(":scope > .group-toggle") : null;
        if (toggle) {
            toggle.classList.toggle("collapsed", !expanded);
            toggle.setAttribute("aria-expanded", String(expanded));
        }
    }

    function init(options) {
        var tree = document.getElementById(options.treeId);
        if (!tree) {
            return {element: null, refresh: function () {}, expandAll: function () {}, collapseAll: function () {}};
        }

        var filterInput = options.filterId ? document.getElementById(options.filterId) : null;
        var emptyEl = options.emptyId ? document.getElementById(options.emptyId) : null;
        var expandAllEl = options.expandAllId ? document.getElementById(options.expandAllId) : null;
        var collapseAllEl = options.collapseAllId ? document.getElementById(options.collapseAllId) : null;

        var rootNodes = Array.prototype.slice.call(tree.querySelectorAll(":scope > .group-node"));

        function snapshotExpansion() {
            var map = new Map();
            tree.querySelectorAll(".group-node").forEach(function (node) {
                var container = childContainer(node);
                if (container) {
                    map.set(node, container.classList.contains("show"));
                }
            });
            return map;
        }

        // Filtering force-opens branches so matches are reachable. To undo that when the
        // filter clears we restore the tree as the user had it when they started typing --
        // not as the server first rendered it, which would silently throw away an
        // Expand All or a branch they had opened by hand.
        var wasFiltering = false;
        var expansionBeforeFilter = null;

        /**
         * Decides visibility for one node and its subtree.
         *
         * A node is shown when it matches, or when one of its descendants does -- otherwise a
         * match nested three levels down would be filtered out along with its parents and be
         * unreachable. A text match also propagates downwards, so searching for a branch
         * reveals what is inside it instead of showing a row that expands to nothing.
         */
        function applyNode(node, query, predicateActive, ancestorTextMatch, filtering, restoreMap) {
            var row = childRow(node);
            var name = row && row.dataset.groupName ? row.dataset.groupName : "";

            var textMatch = ancestorTextMatch || query === "" || name.indexOf(query) !== -1;
            var rowOk = !predicateActive || !options.rowFilter || (row ? options.rowFilter(row) : false);
            var selfVisible = textMatch && rowOk;

            var anyChildVisible = false;
            childNodes(node).forEach(function (child) {
                if (applyNode(child, query, predicateActive, textMatch, filtering, restoreMap)) {
                    anyChildVisible = true;
                }
            });

            var visible = selfVisible || anyChildVisible;
            node.classList.toggle("d-none", !visible);

            if (filtering) {
                // Open the branches that still hold something, so survivors are on screen.
                if (anyChildVisible) {
                    setExpanded(node, true);
                }
            } else if (restoreMap.has(node)) {
                setExpanded(node, restoreMap.get(node));
            }

            return visible;
        }

        function refresh() {
            var query = filterInput ? filterInput.value.trim().toLowerCase() : "";
            var predicateActive = typeof options.rowFilterActive === "function"
                ? !!options.rowFilterActive()
                : false;
            var filtering = query !== "" || predicateActive;

            if (filtering && !wasFiltering) {
                expansionBeforeFilter = snapshotExpansion();
            }
            var restoreMap = expansionBeforeFilter || snapshotExpansion();

            var anyVisible = false;
            rootNodes.forEach(function (node) {
                if (applyNode(node, query, predicateActive, false, filtering, restoreMap)) {
                    anyVisible = true;
                }
            });

            if (!filtering) {
                expansionBeforeFilter = null;
            }
            wasFiltering = filtering;

            if (emptyEl) {
                // Only an active filter can empty the tree; a tree that was empty to begin
                // with has its own server-rendered message.
                emptyEl.classList.toggle("d-none", anyVisible || !filtering);
            }
        }

        function expandAll(expanded) {
            tree.querySelectorAll(".group-node").forEach(function (node) {
                setExpanded(node, expanded);
            });
            // Expanding while a filter is active would otherwise be undone the moment the
            // filter is cleared, because the pre-filter snapshot still says "collapsed".
            if (wasFiltering) {
                expansionBeforeFilter = snapshotExpansion();
            }
        }

        if (filterInput) {
            filterInput.addEventListener("input", refresh);
        }
        if (expandAllEl) {
            expandAllEl.addEventListener("click", function () {
                expandAll(true);
            });
        }
        if (collapseAllEl) {
            collapseAllEl.addEventListener("click", function () {
                expandAll(false);
            });
        }

        return {
            element: tree,
            // Passed straight to addEventListener by callers, so it must not depend on `this`.
            refresh: refresh,
            expandAll: function () { expandAll(true); },
            collapseAll: function () { expandAll(false); }
        };
    }

    global.HierarchyTree = {init: init};
})(window);
