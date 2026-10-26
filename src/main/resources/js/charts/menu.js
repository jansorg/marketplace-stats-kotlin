/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Dropdown menus of the chart dialog, e.g. to export a chart.
 */

/**
 * A mouse opens the menu on hover. A click on the summary keeps it open until the next click, on the summary or
 * outside of the menu. Touch devices and keyboards open it with a tap or Enter on the summary.
 */
function openOnHover(menu, summary) {
    let closeTimer = null;
    let pointerType = null;
    // a menu opened by a click stays open, when the mouse leaves it
    let isPinned = false;
    menu.addEventListener("pointerenter", event => {
        pointerType = event.pointerType;
        if (event.pointerType === "mouse") {
            clearTimeout(closeTimer);
            menu.open = true;
        }
    });
    // a short delay allows to move the mouse across the gap between the summary and the items
    menu.addEventListener("pointerleave", event => {
        if (event.pointerType === "mouse" && !isPinned) {
            closeTimer = setTimeout(() => menu.open = false, 250);
        }
    });
    menu.addEventListener("toggle", () => {
        if (!menu.open) {
            isPinned = false;
        }
    });
    summary.addEventListener("pointerdown", event => pointerType = event.pointerType);
    // the menu is already open by the hover, the first click with the mouse keeps it open, the second one closes it
    summary.addEventListener("click", event => {
        if (event.detail > 0 && pointerType === "mouse") {
            event.preventDefault();
            clearTimeout(closeTimer);
            isPinned = !isPinned;
            menu.open = isPinned;
        }
    });
}

/**
 * An open menu, which doesn't fit below its button in the dialog or the window, opens above it, if there's more space
 * above, e.g. the menu of the window of a line in the legend at the end of the dialog.
 */
function openUpwards(menu, items) {
    menu.classList.remove("chart-menu-up");
    items.style.maxHeight = "";
    const bounds = menu.closest("dialog")?.getBoundingClientRect() ?? {top: 0, bottom: window.innerHeight};
    const bottom = Math.min(bounds.bottom, window.innerHeight);
    const top = Math.max(bounds.top, 0);
    const button = menu.getBoundingClientRect();
    const height = items.getBoundingClientRect().height;
    const isUp = button.bottom + height > bottom && button.top - top > bottom - button.bottom;
    menu.classList.toggle("chart-menu-up", isUp);
    // a long menu scrolls, instead of enlarging the dialog
    limitHeight(items, (isUp ? button.top - top : bottom - button.bottom) - MENU_MARGIN);
}

/** The distance of the items of a menu to the edges of the dialog or the window, in pixels. */
const MENU_MARGIN = 8;

/** Limits the height of the items of a menu to the available space, the items scroll. */
function limitHeight(items, available) {
    // the padding and the border are part of the height
    items.style.boxSizing = "border-box";
    items.style.maxHeight = Math.max(80, available) + "px";
    items.style.overflowY = "auto";
}

/**
 * Places the items of an open menu in the window below or, if there's more space, above its button.
 *
 * @param {boolean} alignEnd The items end at the right edge of the button and open to the left, e.g. for a button at
 *  the right of the dialog.
 * @param {number} maxHeight The maximum height of the items in pixels, a longer menu scrolls.
 */
function placeInWindow(menu, items, alignEnd, maxHeight) {
    items.style.position = "fixed";
    items.style.maxHeight = "";
    const button = menu.getBoundingClientRect();
    const {width, height} = items.getBoundingClientRect();
    const below = window.innerHeight - button.bottom;
    const isBelow = below >= height || below >= button.top;
    // a long menu scrolls within the window
    const available = Math.min(maxHeight, (isBelow ? below : button.top) - MENU_MARGIN);
    limitHeight(items, available);
    const shownHeight = Math.min(height, Math.max(80, available));
    const top = isBelow ? button.bottom + 2 : button.top - shownHeight - 2;
    items.style.top = Math.max(0, top) + "px";
    const left = alignEnd ? button.right - width : button.left;
    items.style.left = Math.max(0, Math.min(left, window.innerWidth - width)) + "px";
    items.style.right = "auto";
    items.style.bottom = "auto";
}

/**
 * A click outside of an open menu closes it, see {@link ChartDialog}.
 *
 * @param {string} label The text of the button, which opens the menu.
 * @param {{fixed?: boolean, hover?: boolean, alignEnd?: boolean, maxHeight?: number}} [options] `fixed` places the
 *  items in the window, e.g. for a menu in a scrolled container, which would cut off the items. Without `hover`, only a
 *  click opens the menu, e.g. a small icon. `alignEnd` opens the items of a fixed menu to the left of the button,
 *  `maxHeight` limits their height in pixels.
 * @return {{menu: HTMLDetailsElement, items: HTMLElement}} The menu and the element for its items.
 */
export function createMenu(label, {fixed = false, hover = true, alignEnd = false, maxHeight = Infinity} = {}) {
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
    // the items of a fixed menu never take space in the dialog, also before they're placed
    items.classList.toggle("chart-menu-fixed", fixed);
    menu.addEventListener("toggle", () => {
        if (menu.open) {
            fixed ? placeInWindow(menu, items, alignEnd, maxHeight) : openUpwards(menu, items);
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
