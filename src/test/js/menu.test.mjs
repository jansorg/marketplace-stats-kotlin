/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {clickOpensSubmenu} from "../../main/resources/js/charts/menu.js";

test("a tap of a touch screen or a pen opens the submenu of an item", () => {
    assert.equal(clickOpensSubmenu("touch"), true);
    assert.equal(clickOpensSubmenu("pen"), true);
});

test("a click with the mouse or the keyboard runs the action of an item with a submenu", () => {
    assert.equal(clickOpensSubmenu("mouse"), false);
    assert.equal(clickOpensSubmenu(null), false);
    assert.equal(clickOpensSubmenu(""), false);
});
