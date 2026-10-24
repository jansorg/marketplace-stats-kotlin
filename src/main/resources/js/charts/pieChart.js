/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Pie charts rendered as SVG, with a legend and a tooltip with the values of the row of a slice.
 */

import {CHART_THEMES, preferredChartTheme} from "./lineChart.js";
import {displayColor, pieArcs, slicePath} from "./model.js";

const SVG_NAMESPACE = "http://www.w3.org/2000/svg";
// size of the coordinate system of the SVG, the displayed size is defined by CSS
const SIZE = 260;
const RADIUS = SIZE / 2 - 8;
const CENTER = SIZE / 2;
// distance, which a highlighted slice moves outward
const HIGHLIGHT_OFFSET = 6;

function element(tagName, className, text) {
    const result = document.createElement(tagName);
    if (className) {
        result.className = className;
    }
    if (text !== undefined) {
        result.textContent = text;
    }
    return result;
}

function sliceMark(arc) {
    if (arc.isFullCircle) {
        const circle = document.createElementNS(SVG_NAMESPACE, "circle");
        circle.setAttribute("cx", String(CENTER));
        circle.setAttribute("cy", String(CENTER));
        circle.setAttribute("r", String(RADIUS));
        return circle;
    }
    const path = document.createElementNS(SVG_NAMESPACE, "path");
    path.setAttribute("d", slicePath(arc, CENTER, RADIUS));
    return path;
}

function fillTooltip(tooltip, slice, formatter) {
    tooltip.replaceChildren(
        element("strong", null, slice.label),
        element("div", null, formatter.value(slice.value, "%")),
        ...slice.details.map(([label, text]) => element("div", null, label + ": " + text)),
    );
}

/**
 * Positions the tooltip next to the pointer, inside the panel.
 */
function positionTooltip(tooltip, panel, event) {
    const bounds = panel.getBoundingClientRect();
    const offset = 12;
    const left = Math.min(event.clientX - bounds.left + offset, bounds.width - tooltip.offsetWidth);
    tooltip.style.left = Math.max(0, left) + "px";
    tooltip.style.top = (event.clientY - bounds.top + offset) + "px";
}

/**
 * @param {{label: string, slices: {label: string, value: number, color: string, details: string[][]}[]}} pie
 * @param {HTMLElement} panel The figure element of the chart.
 * @param {{value: Function}} formatter
 * @param {"light"|"dark"} [themeName] The colors of the chart, by default the color scheme of the browser.
 */
export function renderPie(pie, panel, formatter, themeName = preferredChartTheme()) {
    const theme = CHART_THEMES[themeName];
    const colors = pie.slices.map(slice => displayColor(slice.color, theme.isDark));
    const svg = document.createElementNS(SVG_NAMESPACE, "svg");
    svg.setAttribute("viewBox", `0 0 ${SIZE} ${SIZE}`);
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", pie.label + ": " + pie.slices.map(s => s.label + " " + formatter.value(s.value, "%")).join(", "));

    const legend = element("ul", "chart-pie-legend");
    const tooltip = element("div", "chart-tooltip");
    tooltip.hidden = true;

    // the highlighted slice moves outward, the other slices are dimmed
    let highlighted = null;
    const removeHighlight = () => {
        if (highlighted !== null) {
            highlighted.mark.classList.remove("highlighted");
            highlighted.mark.removeAttribute("transform");
            highlighted.legendItem.classList.remove("highlighted");
            highlighted = null;
        }
        svg.classList.remove("has-highlight");
        tooltip.hidden = true;
    };
    const highlight = (mark, legendItem, arc) => {
        if (highlighted?.mark !== mark) {
            removeHighlight();
            highlighted = {mark, legendItem};
            svg.classList.add("has-highlight");
            mark.classList.add("highlighted");
            legendItem.classList.add("highlighted");
            if (!arc.isFullCircle) {
                const dx = Math.cos(arc.midAngle) * HIGHLIGHT_OFFSET;
                const dy = Math.sin(arc.midAngle) * HIGHLIGHT_OFFSET;
                mark.setAttribute("transform", `translate(${dx},${dy})`);
            }
        }
    };
    const showTooltip = (slice, event) => {
        fillTooltip(tooltip, slice, formatter);
        tooltip.hidden = false;
        positionTooltip(tooltip, panel, event);
    };

    const arcs = pieArcs(pie.slices.map(slice => slice.value));
    pie.slices.forEach((slice, index) => {
        const arc = arcs[index];
        const mark = sliceMark(arc);
        mark.setAttribute("fill", colors[index]);
        // 2px gap in the surface color between the slices
        mark.setAttribute("stroke", theme.surfaceColor);
        mark.setAttribute("stroke-width", "2");

        const swatch = element("span", "chart-swatch");
        swatch.style.backgroundColor = colors[index];
        const legendItem = element("li");
        legendItem.append(swatch, element("span", "chart-pie-label", slice.label), element("span", "chart-pie-value", formatter.value(slice.value, "%")));
        legend.append(legendItem);

        mark.addEventListener("pointerenter", () => highlight(mark, legendItem, arc));
        mark.addEventListener("pointermove", event => showTooltip(slice, event));
        mark.addEventListener("pointerdown", event => {
            highlight(mark, legendItem, arc);
            showTooltip(slice, event);
        });
        mark.addEventListener("pointerleave", event => {
            // on touch devices, the tooltip stays until another slice is tapped
            if (event.pointerType !== "touch") {
                removeHighlight();
            }
        });
        svg.append(mark);
    });

    const body = element("div", "chart-pie");
    body.append(svg, legend);
    panel.append(element("figcaption", null, pie.label), body, tooltip);
}
