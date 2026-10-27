/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The rows of large tables are split into tables, which the browser skips while they're off-screen, see TableChunks.
 * The first table has the header and the first rows (data-row-chunks), each table of rows is in a .table-chunk of
 * the same card. The tables of rows get the widths of the columns of the first table, which has a collapsed row with
 * the longest texts of each column, so that its columns fit all rows. Sorting the first table sorts all rows.
 * Without JavaScript, each table has the widths of its own rows.
 */
for (const table of document.querySelectorAll("table[data-row-chunks]")) {
    const card = table.parentElement;
    const chunks = [...card.querySelectorAll(":scope > .table-chunk")];
    const chunkTables = chunks.map(chunk => chunk.querySelector(":scope > table"));
    const headerRow = table.tHead?.rows[0];
    if (!headerRow || chunks.length === 0) {
        continue;
    }

    addWidthsRow(table, headerRow.cells.length, chunkTables);
    const columns = chunkTables.map(chunkTable => {
        const group = document.createElement("colgroup");
        for (let i = 0; i < headerRow.cells.length; i++) {
            group.append(document.createElement("col"));
        }
        chunkTable.prepend(group);
        chunkTable.style.tableLayout = "fixed";
        return [...group.children];
    });

    // the widths of the first table change with the window, e.g. the column of the names
    let scheduled = false;
    const updateWidths = () => {
        scheduled = false;
        const widths = [...headerRow.cells].map(cell => cell.getBoundingClientRect().width);
        const tableWidth = `${table.getBoundingClientRect().width}px`;
        const rows = table.tBodies[0].rows.length;
        const rowHeight = rows > 0 ? table.tBodies[0].getBoundingClientRect().height / rows : 0;
        chunks.forEach((chunk, index) => {
            chunk.style.width = tableWidth;
            chunkTables[index].style.width = tableWidth;
            columns[index].forEach((column, columnIndex) => column.style.width = `${widths[columnIndex]}px`);
            // the estimated height of the skipped rows, the browser remembers the height of rendered rows
            if (rowHeight > 0) {
                chunk.style.containIntrinsicBlockSize = `auto ${Math.round(rowHeight * chunkTables[index].tBodies[0].rows.length)}px`;
            }
        });
    };
    new ResizeObserver(() => {
        if (!scheduled) {
            scheduled = true;
            requestAnimationFrame(updateWidths);
        }
    }).observe(table);

    // sortable.min.js sorts the rows of the first table: all rows are moved into it before and split again after.
    // The listeners are added after the first sorting of the table by sortable.min.js on DOMContentLoaded, which
    // sorts the rows of the server, which are already sorted.
    const firstRows = table.tBodies[0].rows.length;
    const chunkRows = chunkTables.map(chunkTable => chunkTable.tBodies[0].rows.length);
    whenLoaded(() => {
        table.addEventListener("sort-start", () => {
            const body = table.tBodies[0];
            for (const chunkTable of chunkTables) {
                body.append(...chunkTable.tBodies[0].rows);
            }
        });
        table.addEventListener("sort-end", () => {
            const rows = [...table.tBodies[0].rows];
            let start = firstRows;
            chunkTables.forEach((chunkTable, index) => {
                chunkTable.tBodies[0].append(...rows.slice(start, start + chunkRows[index]));
                start += chunkRows[index];
            });
        });
    });
}

/**
 * Adds a collapsed row to the header of the table with a copy of the cell with the longest text of each column of
 * all tables. Collapsed rows are part of the widths of the columns, but aren't shown. Only the texts are compared,
 * reading them doesn't render the skipped rows.
 *
 * @param {HTMLTableElement} table The first table.
 * @param {number} columnCount The number of columns.
 * @param {HTMLTableElement[]} chunkTables The tables of the other rows.
 */
function addWidthsRow(table, columnCount, chunkTables) {
    const longest = new Array(columnCount).fill(null);
    const lengths = new Array(columnCount).fill(-1);
    for (const body of [table.tBodies[0], ...chunkTables.map(chunkTable => chunkTable.tBodies[0])]) {
        for (const row of body.rows) {
            const cells = row.cells;
            for (let i = 0; i < cells.length && i < columnCount; i++) {
                const length = cells[i].textContent.length;
                if (length > lengths[i]) {
                    lengths[i] = length;
                    longest[i] = cells[i];
                }
            }
        }
    }

    const row = document.createElement("tr");
    row.className = "table-chunk-widths";
    row.setAttribute("aria-hidden", "true");
    for (const cell of longest) {
        row.append(cell ? cell.cloneNode(true) : document.createElement("td"));
    }
    table.tHead.append(row);
}

/** @param {() => void} callback Called after DOMContentLoaded. */
function whenLoaded(callback) {
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", callback);
    } else {
        callback();
    }
}
