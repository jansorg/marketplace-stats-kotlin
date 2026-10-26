/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Tables with the attribute data-collapsed-rows only show their first rows, a row with a button below them shows the
 * other rows. Without JavaScript, all rows are shown. The hidden rows stay in the table, e.g. for the charts.
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

    const columnCount = Math.max(...[...table.rows].map(row => [...row.cells].reduce((sum, cell) => sum + cell.colSpan, 0)));
    const expandRow = body.insertRow(shownRows);
    expandRow.className = "table-expand-row";
    const cell = expandRow.insertCell();
    cell.colSpan = columnCount;
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Show all ▾";
    button.title = `Show all ${shownRows + hiddenRows.length} rows`;
    const expand = () => {
        hiddenRows.forEach(row => row.hidden = false);
        expandRow.remove();
    };
    button.addEventListener("click", expand);
    // the event of sortable-tablesort, before the rows are sorted
    table.addEventListener("sort-start", expand, {once: true});
    cell.append(button);
}
