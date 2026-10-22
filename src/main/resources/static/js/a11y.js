/*
 * Accessibility repairs to what the libraries draw (WCAG 2.1 AA; checked by axe-core in CI, a11y/). They run on
 * load and again whenever the page changes, since tables and calendars are drawn and redrawn by script:
 *
 *  - Tabulator: its grid has children a grid may not (its pager, its focusable scrolling body) and a row group
 *    inside a row group; the scrolling body becomes the grid, owning the header row, and the rest presentational.
 *    Its header filters have no name: they take "Filter <column>".
 *  - FullCalendar: its arrow icons are images without a name (hidden; the buttons are named by their title).
 *  - Scrollable regions (wide tables) are reachable from the keyboard, so their content can be scrolled without
 *    a mouse; each is named after the card it is in. (Calendars are drawn at their full height, with no inner
 *    scrolling region: FullCalendar's height "auto".)
 */
(function () {
    "use strict";

    var sequence = 0;
    var FOCUSABLE = "a[href], button:not([disabled]), input:not([type='hidden']):not([disabled]), select, textarea, "
        + "[tabindex]:not([tabindex='-1'])";

    function cardTitle(el) {
        var card = el.closest(".card, section");
        var heading = card && card.querySelector(".card-title, h1, h2, h3, caption");
        return heading ? heading.textContent.trim() : "Scrollable content";
    }

    function repair(root) {
        root.querySelectorAll(".tabulator-header-contents[role='rowgroup']").forEach(function (el) {
            el.setAttribute("role", "presentation");
        });
        root.querySelectorAll(".tabulator-header-filter input:not([aria-label]), .tabulator-header-filter select:not([aria-label])")
            .forEach(function (input) {
                var column = input.closest(".tabulator-col");
                var title = column && column.querySelector(".tabulator-col-title");
                input.setAttribute("aria-label", "Filter " + (title ? title.textContent.trim() : "column"));
            });
        // A Tabulator table as one grid: its scrolling body (which Tabulator makes focusable) is the grid, and owns
        // the header row, so the pager and the body's tab stop are not children a grid may not have.
        root.querySelectorAll(".tabulator").forEach(function (table) {
            var holder = table.querySelector(":scope > .tabulator-tableholder");
            var headers = table.querySelector(":scope > .tabulator-header .tabulator-headers");
            var body = holder && holder.querySelector(":scope > .tabulator-table");
            if (!holder || !headers || !body) {
                return;
            }
            // Tabulator may set its own roles again when it redraws: put these back every time.
            if (table.getAttribute("role") !== "none") {
                table.setAttribute("role", "none");
            }
            table.querySelectorAll(":scope > .tabulator-header, :scope > .tabulator-header > .tabulator-header-contents")
                .forEach(function (el) {
                    if (el.getAttribute("role") !== "none") {
                        el.setAttribute("role", "none");
                    }
                });
            if (!headers.id) {
                headers.id = "a11y-grid-headers-" + (++sequence);
            }
            if (!body.id) {
                body.id = "a11y-grid-body-" + (++sequence);
            }
            if (holder.getAttribute("role") !== "grid") {
                holder.setAttribute("role", "grid");
            }
            if (holder.getAttribute("aria-owns") !== headers.id + " " + body.id) {
                holder.setAttribute("aria-owns", headers.id + " " + body.id);
            }
            if (!holder.getAttribute("aria-label")) {
                holder.setAttribute("aria-label", cardTitle(table));
            }
        });
        root.querySelectorAll(".fc-icon[role='img']").forEach(function (icon) {
            icon.removeAttribute("role");
            icon.setAttribute("aria-hidden", "true");
            var button = icon.closest("button");
            if (button && !button.getAttribute("aria-label") && button.title) {
                button.setAttribute("aria-label", button.title);
            }
        });
        root.querySelectorAll(".table-responsive").forEach(function (el) {
            if (el.hasAttribute("data-a11y-scroll")) {
                return;
            }
            var scrolls = el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1;
            if (scrolls && !el.querySelector(FOCUSABLE)) {
                el.setAttribute("tabindex", "0");
                el.setAttribute("data-a11y-scroll", "");
                if (!el.getAttribute("role")) {
                    el.setAttribute("role", "region");
                }
                if (!el.getAttribute("aria-label")) {
                    el.setAttribute("aria-label", cardTitle(el));
                }
            }
        });
    }

    var pending = false;
    function schedule() {
        if (pending) {
            return;
        }
        pending = true;
        window.requestAnimationFrame(function () {
            pending = false;
            repair(document);
        });
    }

    document.addEventListener("DOMContentLoaded", function () {
        repair(document);
        new MutationObserver(schedule).observe(document.body, {childList: true, subtree: true});
        window.addEventListener("resize", schedule);
    });
})();
