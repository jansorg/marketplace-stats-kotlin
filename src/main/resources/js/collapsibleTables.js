/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Tables with the attribute data-collapsed-rows only show their first rows, a row with a button below them shows the
 * other rows. Tables with sections and the attribute data-collapsed-sections only show their first sections, e.g. the
 * latest years. Without JavaScript, all rows are shown. The hidden rows stay in the table, e.g. for the charts.
 * Sorting a table shows all rows, the sorted rows wouldn't be the first rows of the table.
 */
for (const table of document.querySelectorAll("table[data-collapsed-rows]")) {
    const shownRows = Number(table.dataset.collapsedRows);
    const body = table.tBodies[0];
    const hiddenRows = body ? [...body.rows].slice(shownRows) : [];
    if (hiddenRows.length === 0) {
        continue;
    }

    hiddenRows.forEach(row => row.hidden = true);
    addExpandRow(table, body.insertRow(shownRows), `Show all ${shownRows + hiddenRows.length} rows`, () => {
        hiddenRows.forEach(row => row.hidden = false);
    });
}

for (const table of document.querySelectorAll("table[data-collapsed-sections]")) {
    const shownSections = Number(table.dataset.collapsedSections);
    const hiddenSections = [...table.tBodies].slice(shownSections);
    if (hiddenSections.length === 0) {
        continue;
    }

    hiddenSections.forEach(section => section.hidden = true);
    // a section of its own, so that the stripes of the rows of the sections don't change
    const expandSection = document.createElement("tbody");
    hiddenSections[0].before(expandSection);
    addExpandRow(table, expandSection.insertRow(), `Show all ${shownSections + hiddenSections.length} sections`, () => {
        hiddenSections.forEach(section => section.hidden = false);
        expandSection.remove();
    });
}

/**
 * Turns the [row] into the row with the button, which shows the hidden rows with [expand] and removes the row.
 */
function addExpandRow(table, row, title, expand) {
    const columnCount = Math.max(...[...table.rows].map(row => [...row.cells].reduce((sum, cell) => sum + cell.colSpan, 0)));
    row.className = "table-expand-row";
    const cell = row.insertCell();
    cell.colSpan = columnCount;
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Show all ▾";
    button.title = title;
    const expandAll = () => {
        expand();
        row.remove();
    };
    button.addEventListener("click", expandAll);
    // the event of sortable-tablesort, before the rows are sorted
    table.addEventListener("sort-start", expandAll, {once: true});
    cell.append(button);
}
