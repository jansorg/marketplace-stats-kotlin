/*
 * Copyright (c) 2026 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import {test} from "node:test";
import assert from "node:assert/strict";
import {metricKey} from "../../main/resources/js/charts/tableData.js";

test("the key of a metric contains its window and its moving average", () => {
    assert.equal(metricKey("mrr"), "metric:mrr");
    assert.equal(metricKey("trials", "calendar-month"), "metric:trials@calendar-month");
    assert.equal(metricKey("sales", "year", 3), "metric:sales@year~avg3");
    assert.equal(metricKey("mrr", null, 12), "metric:mrr~avg12");
    // the values and the averages of a metric are different series
    assert.notEqual(metricKey("sales", "year"), metricKey("sales", "year", 1));
});
