/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The dialog, which shows the charts.
 */

import {createMenu} from "./menu.js";
import {MARKER_LABELS} from "./model.js";

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";

/** Arrows pointing to the corners to maximize, and to the center to restore. */
const ICON_PATHS = {
    maximize: "M3 8V3h5 M3 3l6 6 M17 8V3h-5 M17 3l-6 6 M3 12v5h5 M3 17l6-6 M17 12v5h-5 M17 17l-6-6",
    restore: "M8 3v5H3 M8 8L2 2 M12 3v5h5 M12 8l6-6 M8 17v-5H3 M8 12l-6 6 M12 17v-5h5 M12 12l6 6",
};

function icon(name) {
    const svg = document.createElementNS(SVG_NAMESPACE, "svg");
    svg.setAttribute("viewBox", "0 0 20 20");
    svg.setAttribute("aria-hidden", "true");
    svg.classList.add("chart-icon");
    const path = document.createElementNS(SVG_NAMESPACE, "path");
    path.setAttribute("d", ICON_PATHS[name]);
    svg.append(path);
    return svg;
}


function button(text, onClick, className) {
    const result = document.createElement("button");
    result.type = "button";
    result.textContent = text;
    if (className) {
        result.className = className;
    }
    result.addEventListener("click", onClick);
    return result;
}

export class ChartDialog {
    /**
     * @param {object} callbacks
     * @param {() => void} callbacks.onRemoveAll
     * @param {(combine: boolean) => void} callbacks.onCombineUnitsChange
     * @param {(maximized: boolean) => void} callbacks.onMaximizedChange
     * @param {() => void} callbacks.onResize
     * @param {(kind: string, visible: boolean) => void} callbacks.onMarkerVisibilityChange
     * @param {(kind: "min"|"max", visible: boolean) => void} callbacks.onExtremeVisibilityChange
     * @param {(visible: boolean) => void} callbacks.onIncompleteVisibilityChange
     * @param {(visible: boolean) => void} callbacks.onValueTableVisibilityChange
     * @param {(kind: string, visible: boolean) => void} callbacks.onOverlayVisibilityChange
     * @param {string[]} markerKinds The kinds of vertical lines, which are available on this page.
     * @param {boolean} hasLineCharts If the page has columns, which can be shown in line charts.
     */
    constructor({onRemoveAll, onCombineUnitsChange, onMaximizedChange, onResize, onMarkerVisibilityChange, onExtremeVisibilityChange, onIncompleteVisibilityChange, onValueTableVisibilityChange, onOverlayVisibilityChange}, markerKinds, hasLineCharts) {
        this.element = document.createElement("dialog");
        this.element.className = "chart-dialog noprint";
        this.element.setAttribute("aria-labelledby", "chart-dialog-title");

        const title = document.createElement("h2");
        title.id = "chart-dialog-title";
        title.textContent = "Charts";

        this.combineCheckbox = document.createElement("input");
        this.combineCheckbox.type = "checkbox";
        this.combineCheckbox.addEventListener("change", () => onCombineUnitsChange(this.combineCheckbox.checked));
        this.combineLabel = document.createElement("label");
        this.combineLabel.className = "chart-dialog-combine";
        this.combineLabel.title = "Show two units of the same period in one chart, the second unit on the right y-axis";
        this.combineLabel.append(this.combineCheckbox, " Second y-axis");

        this.maximizeButton = button("", () => onMaximizedChange(!this.isMaximized), "chart-dialog-maximize");

        const header = document.createElement("div");
        header.className = "chart-dialog-header";
        header.append(title, this.combineLabel, button("Remove all", onRemoveAll), this.maximizeButton, button("Close", () => this.close()));

        const hint = document.createElement("p");
        hint.className = "chart-dialog-hint";
        hint.textContent = "Close the dialog and click another column header to add it. The key C shows and hides the charts. "
            + "Dotted lines, hollow points and striped bars mark incomplete values, which may still change.";

        // toggles of the vertical lines, with a sample of each line as key, and of the minimum and maximum
        this.markerToggles = document.createElement("div");
        this.markerToggles.className = "chart-marker-toggles";
        this.markerToggles.hidden = !hasLineCharts;
        this.markerCheckboxes = {};
        for (const kind of markerKinds) {
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.checked = false;
            checkbox.addEventListener("change", () => onMarkerVisibilityChange(kind, checkbox.checked));
            const sample = document.createElement("span");
            sample.className = "chart-marker-sample chart-marker-" + kind;
            const label = document.createElement("label");
            label.append(checkbox, sample, MARKER_LABELS[kind] ?? kind);
            this.markerToggles.append(label);
            this.markerCheckboxes[kind] = checkbox;
        }
        // options of the line charts
        const {menu: viewMenu, items: viewOptions} = createMenu("View");
        viewMenu.classList.add("chart-view-menu");
        this.extremeCheckboxes = {
            min: this.addToggle(viewOptions, "Minimum", "Mark the lowest value of each line, incomplete values are ignored",
                visible => onExtremeVisibilityChange("min", visible)),
            max: this.addToggle(viewOptions, "Maximum", "Mark the highest value of each line, incomplete values are ignored",
                visible => onExtremeVisibilityChange("max", visible)),
        };
        this.incompleteCheckbox = this.addToggle(viewOptions, "Incomplete values",
            "Show the values, which may still change, e.g. of the current month", onIncompleteVisibilityChange);
        this.valueTableCheckbox = this.addToggle(viewOptions, "Value table",
            "Show a table with the values at the cursor and one month and one year before and after it instead of the legend",
            onValueTableVisibilityChange);

        // trends, which are only added to suitable lines
        const trendHeading = document.createElement("span");
        trendHeading.className = "chart-menu-heading";
        trendHeading.textContent = "Trend";
        viewOptions.append(trendHeading);
        const monthsOnly = " Only for monthly values with the granularity Month.";
        this.overlayTitles = {};
        this.overlayCheckboxes = Object.fromEntries([
            ["average3", "3-month average", "Average of each month and the two months before it." + monthsOnly],
            ["average12", "12-month average", "Average of each month and the eleven months before it, without seasonal changes." + monthsOnly],
            ["previousYear", "Previous year", "The values of the previous year as a faint line, e.g. to recognize seasonal changes."],
        ].map(([kind, text, title]) => {
            this.overlayTitles[kind] = {text, title};
            return [kind, this.addToggle(viewOptions, text, title, visible => onOverlayVisibilityChange(kind, visible))];
        }));
        this.applicableOverlays = [];
        this.markerToggles.append(viewMenu);

        this.message = document.createElement("p");
        this.message.className = "chart-dialog-message";
        this.message.hidden = true;

        this.panels = document.createElement("div");
        this.panels.className = "chart-panels";

        this.element.append(header, hint, this.markerToggles, this.message, this.panels);
        this.setMaximized(false);
        // a click on the backdrop closes the dialog, a click outside of an open menu closes the menu
        this.element.addEventListener("click", event => {
            if (event.target === this.element) {
                this.close();
            }
            for (const menu of this.element.querySelectorAll("details[open]")) {
                if (!menu.contains(event.target)) {
                    menu.open = false;
                }
            }
        });
        document.body.append(this.element);
        this.followSize(onResize);
    }

    /**
     * The charts follow the size of the dialog. The resize event of the window isn't enough: on tablets, it's fired
     * before the layout of the rotated screen is complete, and the toolbars of mobile browsers change the size of the
     * dialog without it. A page, which is shown again, e.g. after switching apps, is measured again.
     */
    followSize(onResize) {
        let lastSize = null;
        const resize = force => {
            if (!this.isOpen) {
                return;
            }
            const size = `${this.element.clientWidth}x${this.element.clientHeight}`;
            if (force || size !== lastSize) {
                lastSize = size;
                onResize();
            }
        };
        // called after the layout, also for hidden pages
        new ResizeObserver(() => resize(false)).observe(this.element);
        // measured after the layout of the page, which is shown again
        window.addEventListener("pageshow", () => requestAnimationFrame(() => resize(true)));
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "visible") {
                requestAnimationFrame(() => resize(true));
            }
        });
    }

    get isOpen() {
        return this.element.open;
    }

    get isMaximized() {
        return this.element.classList.contains("maximized");
    }

    open() {
        if (!this.isOpen) {
            this.element.showModal();
            this.fixPage();
        }
    }

    /**
     * Safari and the other browsers of iPadOS scroll the page behind a modal dialog with touch gestures on the parts of
     * the dialog, which can't be scrolled, even with `overflow: hidden` on the page. A fixed page can't be scrolled.
     * Its scroll position is restored when the dialog is closed, also with the Escape key.
     */
    fixPage() {
        const scrollY = window.scrollY;
        const style = document.body.style;
        style.position = "fixed";
        style.top = `-${scrollY}px`;
        style.left = "0";
        style.right = "0";
        this.element.addEventListener("close", () => {
            style.position = "";
            style.top = "";
            style.left = "";
            style.right = "";
            window.scrollTo(0, scrollY);
        }, {once: true});
    }

    close() {
        this.element.close();
    }

    showMessage(text) {
        this.message.textContent = text ?? "";
        this.message.hidden = !text;
    }

    setMaximized(maximized) {
        this.element.classList.toggle("maximized", maximized);
        const title = maximized ? "Restore" : "Maximize";
        this.maximizeButton.replaceChildren(icon(maximized ? "restore" : "maximize"));
        this.maximizeButton.title = title;
        this.maximizeButton.setAttribute("aria-label", title);
    }

    /** @param {string[]} shownKinds */
    /** @return {HTMLInputElement} The checkbox of a new option of the line charts. */
    addToggle(container, text, title, onChange) {
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.addEventListener("change", () => onChange(checkbox.checked));
        const label = document.createElement("label");
        label.title = title;
        label.setAttribute("role", "menuitemcheckbox");
        label.append(checkbox, text);
        container.append(label);
        return checkbox;
    }

    /**
     * Trends, which don't apply to any of the shown series, are dimmed.
     *
     * @param {string[]} kinds The trends, which apply to at least one series of the shown line charts.
     */
    setApplicableOverlays(kinds) {
        this.applicableOverlays = kinds;
        for (const [kind, checkbox] of Object.entries(this.overlayCheckboxes)) {
            const label = checkbox.parentElement;
            const isApplicable = kinds.includes(kind);
            label.classList.toggle("chart-option-unavailable", !isApplicable);
            label.title = this.overlayTitles[kind].title + (isApplicable ? "" : "\nNot available for the shown charts.");
        }
    }

    isOverlayApplicable(kind) {
        return this.applicableOverlays.includes(kind);
    }

    /** @return {string} Why a trend isn't shown. */
    overlayHint(kind) {
        const {text, title} = this.overlayTitles[kind];
        return `${text}: not available for the shown charts. ${title}`;
    }

    /** @param {string[]} shownKinds */
    setShownOverlays(shownKinds) {
        for (const [kind, checkbox] of Object.entries(this.overlayCheckboxes)) {
            checkbox.checked = shownKinds.includes(kind);
        }
    }

    setShowValueTable(visible) {
        this.valueTableCheckbox.checked = visible;
    }

    setShowIncomplete(visible) {
        this.incompleteCheckbox.checked = visible;
    }

    /** @param {("min"|"max")[]} shownKinds */
    setShownExtremes(shownKinds) {
        for (const [kind, checkbox] of Object.entries(this.extremeCheckboxes)) {
            checkbox.checked = shownKinds.includes(kind);
        }
    }

    setShownMarkers(shownKinds) {
        for (const [kind, checkbox] of Object.entries(this.markerCheckboxes)) {
            checkbox.checked = shownKinds.includes(kind);
        }
    }

    /**
     * @param {boolean} visible The option is only shown if it would combine charts.
     */
    setCombineUnits(combine, visible) {
        this.combineCheckbox.checked = combine;
        this.combineLabel.hidden = !visible;
    }

    /** @return {HTMLElement} A new, empty figure for a chart. */
    addPanel(className) {
        const panel = document.createElement("figure");
        panel.className = className;
        this.panels.append(panel);
        return panel;
    }

    clearPanels() {
        this.panels.replaceChildren();
        this.markerToggles.querySelector(".chart-toolbar")?.remove();
    }

    /**
     * Shows the toolbar of the first chart at the end of the row of the options, right-aligned.
     *
     * @return {boolean} If the toolbar is shown, the row is hidden on pages without line charts.
     */
    showToolbar(toolbar) {
        if (this.markerToggles.hidden) {
            return false;
        }
        this.markerToggles.append(toolbar);
        return true;
    }

    showEmptyHint() {
        const empty = document.createElement("p");
        empty.textContent = "Click a column header of a table to show its values.";
        this.panels.append(empty);
    }

    /** @return {number} The width of the charts in pixels. */
    get chartWidth() {
        return Math.max(300, this.panels.clientWidth);
    }

    /**
     * Maximized line charts fill the height of the dialog. The heights are corrected after rendering by the
     * measured space, because the heights of the header and the legends are only estimated by {@link chartHeight}.
     *
     * @param {uPlot[]} plots The line charts of the dialog.
     */
    fitChartHeights(plots) {
        const lastPanel = this.panels.lastElementChild;
        if (!this.isMaximized || plots.length === 0 || lastPanel === null) {
            return;
        }
        const dialogBounds = this.element.getBoundingClientRect();
        const contentBottom = lastPanel.getBoundingClientRect().bottom - dialogBounds.top + this.element.scrollTop
            + Number.parseFloat(getComputedStyle(this.element).paddingBottom);
        // positive if there's space left, negative if the charts are too high
        const freeSpace = Math.floor((this.element.clientHeight - contentBottom) / plots.length);
        if (freeSpace !== 0) {
            plots.forEach(plot => plot.setSize({width: plot.width, height: Math.max(200, plot.height + freeSpace)}));
        }
    }

    /**
     * @return {number} The height of each line chart. Maximized charts share the height of the window.
     */
    chartHeight(chartCount) {
        if (!this.isMaximized) {
            return 300;
        }
        // the space for the header and the legends is an estimate
        const available = window.innerHeight - 140 - chartCount * 90;
        return Math.max(300, Math.floor(available / Math.max(1, chartCount)));
    }
}
