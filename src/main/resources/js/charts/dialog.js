/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The dialog, which shows the charts.
 */

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
     * @param {boolean} hasMajorReleases If the charts show major releases, which are explained in the hint.
     */
    constructor({onRemoveAll, onCombineUnitsChange, onMaximizedChange, onResize}, hasMajorReleases) {
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

        this.maximizeButton = button("Maximize", () => onMaximizedChange(!this.isMaximized), "chart-dialog-maximize");

        const header = document.createElement("div");
        header.className = "chart-dialog-header";
        header.append(title, this.combineLabel, button("Remove all", onRemoveAll), this.maximizeButton, button("Close", () => this.close()));

        const hint = document.createElement("p");
        hint.className = "chart-dialog-hint";
        hint.textContent = "Close the dialog and click another column header to add it. "
            + "Grey points are incomplete values, which may still change."
            + (hasMajorReleases ? " Dashed lines mark the first release of a major version." : "");

        this.message = document.createElement("p");
        this.message.className = "chart-dialog-message";
        this.message.hidden = true;

        this.panels = document.createElement("div");
        this.panels.className = "chart-panels";

        this.element.append(header, hint, this.message, this.panels);
        // a click on the backdrop closes the dialog
        this.element.addEventListener("click", event => {
            if (event.target === this.element) {
                this.close();
            }
        });
        document.body.append(this.element);
        window.addEventListener("resize", () => {
            if (this.isOpen) {
                onResize();
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
        }
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
        this.maximizeButton.textContent = maximized ? "Restore" : "Maximize";
        this.maximizeButton.setAttribute("aria-pressed", String(maximized));
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
