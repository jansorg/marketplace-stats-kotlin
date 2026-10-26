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
 * An open menu, which doesn't fit below its button in the dialog or the window, opens above it, if there's more space
 * above, e.g. the menu of the window of a line in the legend at the end of the dialog.
 */
function openUpwards(menu, items) {
    menu.classList.remove("chart-menu-up");
    const bounds = menu.closest("dialog")?.getBoundingClientRect() ?? {top: 0, bottom: window.innerHeight};
    const bottom = Math.min(bounds.bottom, window.innerHeight);
    const top = Math.max(bounds.top, 0);
    const button = menu.getBoundingClientRect();
    const height = items.getBoundingClientRect().height;
    if (button.bottom + height > bottom && button.top - top > bottom - button.bottom) {
        menu.classList.add("chart-menu-up");
    }
}

/**
 * Places the items of an open menu in the window below or, if there's more space, above its button.
 */
function placeInWindow(menu, items) {
    items.style.position = "fixed";
    const button = menu.getBoundingClientRect();
    const {width, height} = items.getBoundingClientRect();
    const below = window.innerHeight - button.bottom;
    const top = below >= height || below >= button.top ? button.bottom + 2 : button.top - height - 2;
    items.style.top = Math.max(0, top) + "px";
    items.style.left = Math.max(0, Math.min(button.left, window.innerWidth - width)) + "px";
    items.style.right = "auto";
    items.style.bottom = "auto";
}

/**
 * A click outside of an open menu closes it, see {@link ChartDialog}.
 *
 * @param {string} label The text of the button, which opens the menu.
 * @param {{fixed?: boolean, hover?: boolean}} [options] `fixed` places the items in the window, e.g. for a menu in a
 *  scrolled container, which would cut off the items. Without `hover`, only a click opens the menu, e.g. a small icon.
 * @return {{menu: HTMLDetailsElement, items: HTMLElement}} The menu and the element for its items.
 */
export function createMenu(label, {fixed = false, hover = true} = {}) {
    const menu = document.createElement("details");
    menu.className = "chart-menu";
    const summary = document.createElement("summary");
    summary.textContent = label;
    const items = document.createElement("div");
    items.className = "chart-menu-items";
    items.setAttribute("role", "menu");
    menu.append(summary, items);
    if (hover) {
        openOnHover(menu, summary);
    }
    menu.addEventListener("toggle", () => {
        if (menu.open) {
            fixed ? placeInWindow(menu, items) : openUpwards(menu, items);
        }
    });
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
