/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Dropdown menus of the chart dialog, e.g. to export a chart.
 */

/**
 * A mouse opens the menu on hover. Touch devices and keyboards open it with a tap or Enter on the summary.
 */
function openOnHover(menu, summary) {
    let closeTimer = null;
    let pointerType = null;
    menu.addEventListener("pointerenter", event => {
        pointerType = event.pointerType;
        if (event.pointerType === "mouse") {
            clearTimeout(closeTimer);
            menu.open = true;
        }
    });
    // a short delay allows to move the mouse across the gap between the summary and the items
    menu.addEventListener("pointerleave", event => {
        if (event.pointerType === "mouse") {
            closeTimer = setTimeout(() => menu.open = false, 250);
        }
    });
    summary.addEventListener("pointerdown", event => pointerType = event.pointerType);
    // the menu is already open, a click with the mouse must not close it
    summary.addEventListener("click", event => {
        if (event.detail > 0 && pointerType === "mouse") {
            event.preventDefault();
            menu.open = true;
        }
    });
}

/**
 * A click outside of an open menu closes it, see {@link ChartDialog}.
 *
 * @param {string} label The text of the button, which opens the menu.
 * @return {{menu: HTMLDetailsElement, items: HTMLElement}} The menu and the element for its items.
 */
export function createMenu(label) {
    const menu = document.createElement("details");
    menu.className = "chart-menu";
    const summary = document.createElement("summary");
    summary.textContent = label;
    const items = document.createElement("div");
    items.className = "chart-menu-items";
    items.setAttribute("role", "menu");
    menu.append(summary, items);
    openOnHover(menu, summary);
    // Escape closes the menu, but not the dialog
    menu.addEventListener("keydown", event => {
        if (event.key === "Escape" && menu.open) {
            event.preventDefault();
            event.stopPropagation();
            menu.open = false;
            summary.focus();
        }
    });
    return {menu, items};
}
