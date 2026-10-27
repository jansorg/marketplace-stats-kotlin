# AGENTS.md

## Project Overview

Marketplace Stats provides sales and usage statistics for paid plugins on the [JetBrains Marketplace](https://plugins.jetbrains.com/).
The project consists of:

- an HTTP API client for the JetBrains Marketplace (and the JetBrains product/release services), and
- a web application built on top of it, which loads marketplace data and renders reports as server-side HTML.

It's written in Kotlin and licensed under AGPL-3.0-or-later.

## Modules

| Module                | Purpose                                                                                                                                                                                                                                         |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `marketplace-client/` | Ktor-based HTTP client for the Marketplace API (`client/`), caching wrapper (`CaffeineCacheMarketplaceClient`), persistent disk cache (`client/persistentCache/`), API models (`client/model/`), JetBrains product/release services (`services/`), currency handling and exchange rates (`exchangeRate/`). |
| `marketplace-data/`   | Aggregation and analysis of marketplace data: report tables in `data/*` (overview, customers, licenses, trials, churn, funnel, countries, ...), trackers, formatting helpers.                                                                    |
| root project (`src/`) | Ktor/Netty web server and CLI entry point (`dev.ja.marketplace.ApplicationKt`), page definitions, JTE templates (`src/main/resources/templates/*.kte`), CSS and JS assets.                                                                        |

Dependency direction: root → `marketplace-data` → `marketplace-client`. All Kotlin sources live in package `dev.ja.marketplace`.

## Tech Stack

- Gradle 9.8 with the Kotlin DSL and configuration cache enabled
- Kotlin 2.3.21 (JVM plugin and serialization plugin)
- Java: built and run with JDK 27 (Gradle toolchain, CI, Docker image `eclipse-temurin:27-jre`). The bytecode targets
  Java 25 because Kotlin 2.3 can't emit JVM 27 bytecode. Gradle uses a locally installed JDK 27 or downloads one via the
  foojay toolchain resolver (`settings.gradle.kts`). The default `java` on the PATH can be an older version.
- Ktor 3.6 (client with the Java engine, server with Netty), kotlinx-serialization 1.11, kotlinx-coroutines 1.11,
  kotlinx-datetime 0.8 (the `0.6.x-compat` variant, which Ktor requires)
- JTE 3.2.4 templates (`.kte`, Kotlin syntax), precompiled at build time
- Clikt 5 for the CLI (`Application.kt`)
- MCP Kotlin SDK (`io.modelcontextprotocol:kotlin-sdk-server`) for the MCP server at `/mcp`
- Moneta (JSR 354, `moneta-core` only) for monetary amounts. Currency conversion uses the ECB reference rates, loaded
  lazily by our own `EcbExchangeRateData` (`marketplace-client/.../exchangeRate/`), not by Moneta's ECB providers.
- fastutil for collections on hot paths (per-license sets and maps), Caffeine for caching, SLF4J for logging in the
  modules and Logback as its backend in the web application
- JUnit 6 for tests
- Gradle plugins: Shadow (fat JAR), gmazzo BuildConfig (`BuildConfig.APP_VERSION` from `VERSION.txt`), `maven-publish`
  (the modules as libraries, see "Publishing the Libraries")

All dependencies and plugin versions are declared centrally in the root `build.gradle.kts`; the Ktor version is in `gradle.properties`.

Version constraints to keep in mind when upgrading:

- The JTE Gradle plugin (`gg.jte.gradle`) and the JTE runtime (`gg.jte:jte`) must have the same version, and it must match
  the JTE version required by `ktor-server-jte`. A mismatch fails `precompileJte` with an obscure Kotlin compiler error
  (`source must not be null`).
- JTE compiles `.kte` templates with its own embedded Kotlin compiler (`jte-kotlin` 3.2.4 bundles Kotlin 2.2.20). That compiler
  can read class metadata up to Kotlin 2.3. If anything on the runtime classpath pulls in `kotlin-stdlib` 2.4 or newer,
  `precompileJte` fails with `Module was compiled with an incompatible version of Kotlin`. For that reason the project
  stays on Kotlin 2.3, and some dependencies are intentionally not on their latest version:
  - `kotlinx-serialization-json` 1.11.0 (1.12 requires Kotlin 2.4)
  - `dev.hsbrysk:caffeine-coroutines` 2.0.4 (2.0.5 requires Kotlin 2.4)
  - `kotlinx-serialization-protobuf` (format of the persistent cache) must have the same version as `kotlinx-serialization-json`
  - `io.modelcontextprotocol:kotlin-sdk-server` 0.14.0 (0.15 requires Kotlin 2.4). It depends on older Ktor modules,
    the `ktor-bom` platform aligns them with the Ktor version of the project.

  Before upgrading a Kotlin library, check the `kotlin-stdlib` version it requires, and afterwards check
  `./gradlew dependencyInsight --configuration runtimeClasspath --dependency org.jetbrains.kotlin:kotlin-stdlib`.
- The user may run a server from the IDE, which uses the build output of the working tree (`build/classes`,
  `build/jte-classes`, `marketplace-data/build/libs/*.jar`). Running Gradle in the working tree replaces these files, and
  classes loaded afterwards fail with `NoClassDefFoundError`. To test changes while such a server is running, copy the
  working tree (without `build/` and `.gradle/`) to a temporary directory and build there.
- The fat JAR is built with Shadow's `minimize` (`shadowJar` in `build.gradle.kts`). Libraries, which load classes by
  name or via `ServiceLoader` (Logback, Ktor, Mordant, Caffeine, Moneta, JTE, MCP SDK, kotlin-logging), are excluded from minimizing. The root
  project depends on the modules with `api`, so that classes only used by the JTE templates are kept. After adding or
  upgrading a dependency, run the fat JAR and open the report pages. Shadow's build cache doesn't notice changes of the
  `minimize` settings, use `./gradlew shadowJar --no-build-cache --rerun-tasks`.
- Don't rebuild the fat JAR while a server is running from `build/libs/marketplace-stats-all.jar`. Classes that are
  loaded lazily afterwards fail with `NoClassDefFoundError`, which crashes Netty's event loop threads and makes requests
  hang. For profiling or comparisons, run copies of the JAR.
- After dependency upgrades, run `./gradlew build` (it includes `precompileJte` and the tests) and do a quick smoke test of
  the web server (see below).

## Common Commands

```bash
./gradlew build                          # compile, precompile JTE templates, run tests
./gradlew test                           # run all tests
./gradlew :marketplace-client:test       # run tests of a single module
./gradlew shadowJar                      # build/libs/marketplace-stats-all.jar (fat JAR)
```

CI (`.github/workflows/CI.yaml`) runs `./gradlew build` with Temurin JDK 27.

The client decodes the API responses with `ignoreUnknownKeys`, so new properties of the API don't break it, but they
would go unnoticed. `LiveApiCoverageTest` (`marketplace-client`) requests all GET endpoints of the Marketplace and the
JetBrains services, and fails for properties of the responses and columns of the sales reports, which the models don't
have (`UnknownJsonKeys`, `SalesReportCsv.unknownColumns`), and for responses the client can't decode. It only runs with
`MARKETPLACE_API_KEY` and `MARKETPLACE_COVERAGE_PLUGIN_IDS` (comma-separated IDs of paid and free plugins of the account),
e.g. after API changes: `./gradlew :marketplace-client:test --tests '*LiveApiCoverageTest' --rerun`. New properties are
added to the models as optional properties at the end of the constructors. Values of the API, which the Marketplace may
extend, must not fail a response:
- Enums, which the code handles in `when` expressions, e.g. `LicensePeriod`, `CustomerType` and `PluginSaleItemType`, have
  an entry `Unknown` and use a `TolerantEnumSerializer`, which decodes unknown values as `Unknown` and logs them once.
  Tables with fixed rows per value skip `Unknown`, unless there are sales with it. The persistent cache stores it as code 0.
- Open sets of values, e.g. `ProductFamily`, `PluginRatingCategory` or `ProductDownloadType`, are sealed interfaces with a
  `data object` for each known value and a data class for unknown values. `findById` returns the predefined objects and
  creates an unknown value once per ID.

A plugin can disable the continuity discount at a date: licenses bought before keep it for their renewals, later
licenses pay the price of the 1st year every year. The API may return `continuityDiscountDisabledAt` of
`MarketplacePluginInfo` without `hasContinuityDiscount`, `PricingOverviewTable.hasContinuityDiscount` combines both.
The calculations use the billed amounts and don't need it. The pricing page shows a single annual price without
prices of the 2nd and 3rd year, its description and `get_pricing` (`continuityDiscount`) tell when it was disabled.

The plugin search (`/api/searchPlugins`) returns pages of at most 24 plugins (`Marketplace.MAX_SEARCH_RESULT_SIZE`) and
only the first 10,000 results of a query (`MAX_SEARCH_RESULT_WINDOW`): `offset + max` beyond it is answered with 400,
and the reported total is capped at 10,000. `tags` are combined with OR, `excludeTags` excludes plugins with any of the
tags. `MarketplacePluginSearchRequest.searchMode` selects how `marketplacePluginsSearch` loads the results:
`PluginSearchMode.Marketplace` (the default) returns the results of the window in the requested order.
`AnySizeWorkaround` returns all results: if the query has more results than the window, it ignores the requested order
and loads disjoint queries, one per tag of `/api/tags` (`marketplaceTags`), which excludes the tags of the previous
queries, and one which excludes all these tags. The results are in the order of these queries.

## Running the Web Server

```bash
./gradlew precompileJte run --args="-d normal --host 127.0.0.1 --port 18080 config.json"
```

- `config.json` (git-ignored) holds the API key. Its supported keys are `apiKey`, `displayedCurrency`,
  `showResellerCharges`, `cacheDir` and `eventsFile` (see `ApplicationConfig.kt`). The host and port can't be set in the config file. They come from
  `--host`/`--port` or `MARKETPLACE_SERVER_HOSTNAME`/`MARKETPLACE_SERVER_PORT`, and default to `0.0.0.0:8080`.
- Pass `--port` explicitly and use a port other than 8080, so the server doesn't collide with an instance the user
  is already running. Bind to `127.0.0.1`.
- `-h` is `--host`, not help. Use `--help` for usage. `-d none|normal|verbose` sets the log level.
- The server blocks the terminal. Start it in the background, wait for the line `Launching web server: http://<host>:<port>/`,
  and stop it when you're done.
- Before printing that line, the server loads the plugin list and the countries from the JetBrains APIs. Plugin data is
  loaded lazily on the first request for a plugin and then cached in memory, so the first page load for a plugin is slow.
  For testing, prefer a plugin with few sales, because plugins with a long sales history take much longer to load.
- Useful routes: `/` (plugin list with the summary of all paid plugins, `--no-all-plugins-summary` disables it, or report with `?pluginId=<id>`), `/licenses`, `/customers`, `/trials`, `/countries`,
  `/pricing`, `/offers` (see "Offers"), `/downloads/monthly`, `/chart-data` (JSON), `/chart-export` (CSV or JSON of the lines of a chart, see "Charts"), all with `?pluginId=<id>`. `POST /refresh?pluginId=<id>&reload=true` drops cached data.
  See the routing in `MarketplaceStatsServer.kt`.
- The MCP server is served at `/mcp` (Streamable HTTP, `src/main/kotlin/dev/ja/marketplace/mcp/`, see README.md).
  Tools return JSON (`McpToolDefinition.call`), tools with images also a `McpToolResult` with `McpContent`
  (`McpToolDefinition.result`), e.g. `create_chart` (`McpChartTool`), which renders charts of `ChartMetricValues`
  as PNG (image content) or SVG (text content).
  It's disabled with `--no-mcp`. Requests must use one of the allowed hostnames (`localhost`, `127.0.0.1`, `[::1]`, `--host`,
  `--mcp-allowed-hosts`) in the `Host` header. The server is stateless, it has no sessions: send each request as POST with
  `Accept: application/json, text/event-stream`, a tool call doesn't need an `initialize` before. GET and DELETE return 405.
- A JetBrains Marketplace API key is required. Besides the config file, it can be passed via `--api-key` or the
  `MARKETPLACE_API_KEY` environment variable.
- `config.json`, `config.*.json` and `.env` are git-ignored and may contain real API keys. Never commit them or print their contents.
- See `README.md` for all CLI options and environment variables.

## Persistent Cache

- The durations of all caches, in memory and on disk, are defined by `CachingStrategy` (`marketplace-client/.../client/`).
  Data is grouped by how often it changes: `Recent` (30 minutes, e.g. recent sales and trials, offers, reviews),
  `Daily` (e.g. downloads, ratings, releases, products of JetBrains) and `Static` (7 days, e.g. prices, sales older than
  45 days, the weekly complete fetch of the history). Use these categories for new cached requests.
- The Marketplace publishes the downloads of a day late, during the next day, until then they're 0. Downloads, which
  were fetched before a response grouped by day contained the downloads of yesterday, are `Recent`, in memory and on
  disk (`DownloadsCompleteness`). `downloadsMonthly`, `downloadsDaily` and `downloadsByProduct` aren't cached
  themselves, they use the cached `downloads()`.
- With `--auto-refresh[=<minutes>]`/`MARKETPLACE_AUTO_REFRESH` (off by default, 30 minutes), `PluginAutoRefresh` refreshes
  the plugins, which were requested in the last 24 hours (`MarketplaceStatsServer.getDataLoader`, the summary of all
  plugins), via `CaffeineCacheMarketplaceClient.refreshPlugin`. Requests then use the cached data for two more
  intervals (`CachingStrategy.expiry`) and don't fetch it themselves. A new cached request needs a `refresh` function,
  which bypasses the persistent caches, if its loader reads them.
- Optional, enabled with `--cache-dir`/`MARKETPLACE_CACHE_DIR`/`cacheDir`. Without it, or with an empty value, all data
  is only cached in memory. The Docker image enables it with `MARKETPLACE_CACHE_DIR=/root/.cache/marketplace-stats`,
  `docker-compose.yml` mounts a named volume there.
- Code in `marketplace-client/.../client/persistentCache/`. `PersistentCacheStore` writes gzip-compressed ProtoBuf files
  with a header (magic, format version, data version), atomically via a temporary file. Unreadable files and files of
  another version are deleted and the data is fetched again.
- Stored: sales and trials (`PersistentHistoryCache`, incremental fetches with an overlap of 45 days after each start,
  for all plugins with stored data via `CaffeineCacheMarketplaceClient.updateStoredHistory`, and when a report needs
  data older than 30 minutes, complete fetch weekly),
  plugin prices by country (`PersistentPriceCache`), the ECB exchange rates (`EcbExchangeRateData`), downloads
  (`PersistentResponseCache`, responses of `downloads`, `downloadsTotal` and `groupedDownloads` as JSON, fetched again after
  a day, see `DownloadsCompleteness`) and the products and releases of JetBrains (`CachingJetBrainsServiceClient`, in `jetbrains/`, shared by all
  accounts, fetched again after a day).
  The data of an account is stored in `accounts/account-<hash of API host and key>/`.
- The stored classes (`CachedSalesModel.kt`, `CachedEcbRates`) are separate from the API model. After incompatible
  changes, increment the data version (`HistoryKind`, `DATA_VERSION`). `CachedSalesModelCoverageTest` fails if a
  property of the API model isn't stored.
- The cache contains real sales data, delete test caches after use.

## Cached Tables

- `DefaultPluginPageDefinition` reuses the calculated tables of a page (`VersionedCache`, by plugin and `rows`, at
  most 20 keys per page, soft values, which the garbage collector removes when the memory gets scarce) while
  the data of the plugin is the same: `PluginData.dataVersion()` contains today, the currency, the version of the
  exchange rates (`ExchangeRates.dataVersion`, a new object after an update of the ECB rates) and all loaded data.
  The cached client returns the same objects until it loads data again (expiry, refresh, reload, auto-refresh,
  background updates of the stored history), the values are compared by identity and then by content.
- Data, which a table loads while it's calculated, isn't part of the version. Such pages pass `cacheTables = false`,
  e.g. the pricing page, whose table loads the prices. New data of `PluginData` must be added to `dataVersion()`.

## Offers

- The page `/offers` (`OffersTable`, `marketplace-data/.../data/offers/`) has two tables: "Marketplace" (the discounts
  of the Marketplace without dates: the continuity discounts of renewals, `OfferUsages.continuityDiscounts`, and the
  specials, `OfferUsages.marketplaceSpecials`: the student, graduation, startup, developer recognition, educational,
  non-profit, open source and subscription volume discounts, `OfferDiscounts.isMarketplaceSpecial`, by the name of the
  offer or the description of the discount, offers of the plugin with such a name stay offers; the names of the
  specials contain their percents) and "Offers" with the sections "Active" (offers, which haven't ended and have an
  active coupon, and the other offers and discounts, which were used in the last 90 days,
  `OffersTable.RECENT_USE_DAYS`) and "Inactive" (the other offers of the plugin, also if they were used recently, e.g.
  an offer with an end date in the past, and the other offers and discounts, which weren't used recently). The
  sections share the widths of the columns, like the years of the overview, the table isn't sortable, because the
  header rows of the sections would be sorted. With a single section, the table has the title of the section. The rows
  are sorted by their latest use. The tables load the offers and their coupons with the client while they're
  calculated, so the page passes `cacheTables = false`. Empty tables are left out (`hideEmptyTables`).
- The sales don't contain the ID of an offer. A discount described as `"<name>" offer` belongs to the offer with this
  name (`OfferDiscounts.offerName`, `OfferUsages`), offers with the same name are told apart by their dates and
  license periods. It's a heuristic: a renamed offer doesn't match its earlier sales. Offer discounts without an offer
  of the plugin (e.g. offers of JetBrains like student discounts or deleted offers) and discounts without an offer are
  other offers and discounts. Reseller discounts are ignored, they're internal discounts of the Marketplace and don't
  reduce the amount paid to the developer.
- The discount amount (column "Discount Amount", `OfferUsage.lost`) is derived from the paid amount of each license
  and the percent of its discount in the sale (`paid * p / (100 - p)`, `OfferDiscounts.lostAmount`), not from the
  percent of the offer, which may have changed for offers with the same name, and summed with `MonetaryAmountTracker`:
  the price before this discount, after the other discounts, minus the paid amount. Free licenses (100 %) and
  discounts without a percent have no lost amount, the API has no undiscounted amount.
- The chart metrics `discount.<name of the discount as Base64 URL-safe without padding>` (`ChartMetric.discount`,
  group "Discounts" of the "+" menu) are the discount amounts of the offers and discounts by their name in the sales:
  the name of the offer, or the description of a discount without an offer (`OfferDiscounts.discountName`), calculated
  like the column "Discount Amount". Each plugin has the metrics of the discounts of its sales with a discount amount,
  they aren't part of `ChartMetric.all`, the summary of all plugins doesn't have them. The names of the rows with a
  discount amount have a button, which adds the line of the discount to the charts, a click on the name, which is
  marked like the labels of the columns with charts, too (`ChartMetricName`, rendered by `render.kte`,
  `makeRowChartButtons` in `pageControls.js`, `showRowMetric` in `main.js`, with the window "Follow the level"),
  instead of a chart of each row of a column. The MCP tool `get_offer_usage` returns the tables of the page and these
  metrics (`chartMetrics`), `create_chart` accepts them.
- The numbers of sales and customers link to `/licenses` and `/customers` with the filter `offer=<id>`,
  `otherOffer=<name>` or `discount=<description>` (`LinkedOfferUsage`, `OfferUsageKey`), which list the licenses and
  customers with the discount. The links limit the rows (`rows=500`, `OfferUsageList.DEFAULT_ROWS`), "Show all rows…"
  (`POST /refresh`) keeps the filter.

## Charts

- The charts are ES modules in `src/main/resources/js/charts/` (`main.js` is the entry point). The task
  `bundleChartsJavascript` bundles and minifies them with the Closure Compiler (a Java library, no Node.js needed) into
  `js/charts.min.js` with a source map, which the pages load. `js/collapsibleTables.js` is minified the same way
  (task class `ClosureBundle` in `build.gradle.kts`, it lists the files when it runs, so that new modules are bundled
  also with the configuration cache). The sources aren't part of the resources, a change needs a build. Line charts use uPlot, pie charts are SVG. Keep logic out of the DOM code:
  - `format.js`, `model.js`, `storage.js`: pure functions, tested with Node.js in `src/test/js/*.test.mjs`
    (`node --test src/test/js/`, or `./gradlew testJavascript`, which is part of `check` and skipped without Node.js)
  - `tableData.js`: reads the `data-chart-*` attributes of the tables
  - `metricValues.js`: the series of metrics and the cache of the values of `/chart-data` (`MetricValueCache`), tested
    like the pure functions, `endpoints.js`: the requests to the server
  - `lineChart.js`, `pieChart.js`, `dialog.js`, `menu.js` (dropdown menus), `addMenu.js` (the "+" menu),
    `chartControls.js` (the switches of the level and the style), `chartExports.js` (the Export menu),
    `pageControls.js` (column headers, logo and the key C): rendering, `main.js`: state of the charts
  - `exportImage.js`: images of the charts with title and legend, for downloads (PNG, pie charts also SVG) and
    printing. Printing adds the images to the page and `print.css` hides everything else (`body.print-charts`).
- The key C shows and hides the chart dialog.
- The View option "Hide axis values" (`hideAxisValues`, off by default) hides the values of the y-axes and of the
  minimum and maximum, e.g. to share a chart without numbers. The View option "Logarithmic y-axis" (`logScale`, off by default)
  shows the y-axes on a logarithmic scale (uPlot `distr: 3`, the powers of ten around the values, `logRange` in
  `model.js`). Values of 0 and below have no position on it and aren't drawn (`logScaleValues`), the value table and
  the tooltip still show them, bars start at the bottom of the axis, the ticks of two y-axes aren't aligned. The chart
  images of the server have it, too (`create_chart` with `logScale`, `ChartAxes.logRange`). "Copy PNG" of the Export menu copies the image of
  "Download PNG" into the clipboard (`copyCanvas`, only in a secure context like localhost).
- The cursor of a line chart snaps to the closest value. With the option "Comparison lines" of the View menu
  (`showComparisonLines`, off by default), dashed and dotted lines mark the dates one year and one month before and
  after it. The table below the chart compares the values at these dates (`enableComparisons` in `lineChart.js`). The table replaces the uPlot legend, it lists each series followed by its trends, e.g. its moving
  averages (`valueTableRows`). A click on the label of a line only shows or hides this line, not the trends of a
  series. Without the option "Value table" of the View menu, the uPlot legend is shown instead, it only shows and
  hides the lines.
- Columns declare what they measure with `DataTableColumn.chartMeasure` (`ChartMeasure`: flow, stock or ratio), written
  as `data-chart-measure`. The trends of the View menu (1-, 3- and 12-month moving averages, previous year) are only added
  to suitable series (`overlayApplies` in `model.js`). The moving averages of metrics come from `/chart-data` (see
  below), of the values of tables they're calculated by `trailingAverage` (3 and 12 months of monthly values). Options
  without effect on the shown charts are dimmed in the menu.
- Two y-axes have their ticks at the same heights (`alignedRanges` in `model.js`), so that the grid lines match the
  values of both axes. An axis without shown lines is hidden, the other axis then has the grid (`showUsedAxes`).
- Incomplete values are drawn as dotted lines, hollow points and striped bars in a lighter color of their series
  (`incompleteColor` in `model.js`, darker in the dark mode).
- A new series gets the first unused color of `SERIES_COLORS`, which is distinct from the colors of the other series
  of its chart (`nextColor` and `colorDistance` in `model.js`, `ChartColor.next` of the chart images): the distance in
  Oklab, also with simulated deuteranopia and protanopia, and a line over bars needs a different lightness, e.g. a
  yellow line over blue bars instead of green. Without such a color, it gets the most distinct unused color. Stored
  colors are kept.
- Colors of the dark mode: `CHART_THEMES` in `lineChart.js` (axes, grid, surface, vertical lines) and
  `DARK_SERIES_COLORS` in `model.js`. The stored chart setup always uses the light series colors, `displayColor` maps
  them when the charts are drawn. Exported and printed images always use the light colors, a dark chart is drawn again
  with the light colors for the image.
  The option "Incomplete values" of the View menu of the chart dialog hides them, "Minimum" and "Maximum" mark the
  lowest and highest complete value of each series.
- In sortable tables (`sortable` CSS class), a click on the label of a column header opens the chart and a click on
  the rest of the header, e.g. the arrow, sorts the table.
- Charts of metrics: columns with `DataTableColumn.chartMetric` (written as `data-chart-metric`, with
  `data-chart-unit`) show a metric (`ChartMetric` in `marketplace-data/.../data/chart/`, e.g. `sales`, `mrr`,
  `churn-annual`, `customer-type-share.<type>`). The same metric of different tables is the same series
  (`metric:<id>`), and series of the same unit share a chart. The values aren't read from the table, they're loaded
  from `/chart-data?pluginId=<id>&metrics=<ids>&window=<window>&detail=<detail>` (`ChartData`, without `pluginId` the
  summary of all paid plugins, errors are answered with 400 or 404 as text). `ChartMetricValues` calculates the values of each day lazily for a metric and a window,
  with the rules of the tables:
  - Window (`ChartWindow`): the sums and rates of each date are those of the window, which ends at the date. Rolling
    windows: day, week, month, quarter, year (the 12 months before the date), at the end of a period the calendar
    period. Calendar windows (`calendar-month` etc.) start at the first day of the period of each date, so the value at
    today is the value of the table. Values at a date (stocks like the MRR, `ChartMetric.usesWindow`) and the revenue
    retention (compared with a year ago, like the table) ignore the window. `SubscriptionDailyValues` calculates the
    movements of the MRR per window and the values at each day once.
  - Detail level (`ChartDetail`): the dates of the values, each day or the end of each week, month or year, and today.
  - Each line has its own window (`window` of the series, the key is `metric:<id>@<window>`), so that e.g. the trials
    of each calendar month and the trials of each day are two lines of one chart. The gear of a line in the legend
    (`seriesMenu` in `lineChart.js`) selects its window and its type (like the chart, line or bars, `style` of the
    series and of the stored entry, also for lines of tables). `seriesMenuSections` in `model.js` selects the sections:
    the window only for lines, whose values depend on it, the type not for moving averages, which are always lines
    (`seriesStyle`), the buttons Year/Month/Week/Day set the detail level of
    the chart, stored as `chartLevels`. The window "Follow the level" (`follow`, only in the charts) is the calendar
    window of the level, e.g. the calendar year at the level Year. Lines of tables with the calendar window of their
    level follow the level (`tableWindow`). The "+" menu of the dialog adds a metric of `/chart-metrics` (the metrics
    of the page, grouped by `ChartMetric.group`) with a selected window to the chart of its unit. The tooltip of a
    metric in the menu is its `ChartMetric.description`, a new metric needs one.
  - Menus open above their button, if they don't fit below (`menu.js`), menus in scrolled containers like the legend
    are placed in the window (`createMenu(label, {fixed: true})`, `position: fixed`), a long list, e.g. the "+" menu,
    which uses the height of the window on touch screens, scrolls with a finger (`touch-action: pan-y`). The items must
    not be a popover: Chromium hangs, when a popover in a `<details>` is opened again by a click. A new chart takes the level of the table (`DataTable.chartLevel`, `data-chart-window` and
    `data-chart-detail`), e.g. calendar month and month for the overview. A window, which is shorter than the steps
    of the dates (`windowApplies` in `model.js`), e.g. a day at the level Year, is disabled, a change of the level
    replaces it with the calendar window of the level.
  - Moving averages: `<metric>@avg<months>` (1, 3 or 12), `ChartMetricValues.average`, are lines of their own
    (`average` of the series), which the submenu of a metric in the "+" menu adds, a click on the metric adds its values. A mouse opens the submenu
    on hover beside the metric. Touch screens and pens have no hover: a tap on the metric opens and closes the submenu
    below it, within the list (`clickOpensSubmenu` in `menu.js`, class `chart-add-inline`). The View menu only has the
    trend "Previous year". Lines of averages have no points, next to lines of values they're dashed (`averageDashOf`). The × of a line in the legend removes it. The windows, which end at the
    date and before each previous window, fill the months: sums are divided by the number of windows (e.g. the average
    month), rates are pooled from their numerators and denominators (`DailyRatio`, e.g. churned and previously active
    licenses), values at a date are averaged over the days. Calendar windows use their rolling windows. The
    average of a window, which isn't shorter than the months, is its value.
  - New metrics are registered in `ChartMetric` and calculated in `ChartMetricValues.calculate`, new windows only need
    `ChartWindow.start` and `currentPeriodStart`.
  - Decimals: `ChartMetric.fractionDigits` is the maximum number of decimals of the shown values of a metric, e.g. of
    its moving averages. By default counts at a date (e.g. the active licenses) have none, counts of a window (e.g. the
    trials) one, the other metrics two. `/chart-data`, the JSON of `/chart-export` and the MCP chart images pass it on,
    the values themselves and the CSV of `/chart-export` aren't rounded.
  - Churn (`churn-annual`, `churn-monthly`, the columns of the overview, `churn-combined`, the column "Churn (all)" of
    the tables of the years of a plugin and of all plugins): the paid licenses, which
    were active at the day before the window and aren't active at its end (`LicenseChurnProcessor`, `ChurnValidities`
    with the grace period of renewals). `isCountedByChurn` selects the licenses, the combined churn (`period` `null`)
    counts the paid licenses of all periods, it's the logo churn of the window: annual licenses only churn in the
    window of their renewal, so the value of a month is a monthly rate and the value of a year an annual rate. A
    license, which continues as a free license or with another period, isn't churned. The link `/churn-rate/all/...`
    lists the churned licenses of the combined churn. The summary of all plugins pools the licenses of each plugin
    (`ChurnResult.plus`, the IDs of licenses of different plugins could be the same), its rate has no link.
  - New customers (`new-customers`, `ChartMetricValues.newCustomerDays`): a customer counts once, at the day of the
    first paid license of the plugin. Free licenses and returning customers don't count, and a customer who got a
    license by a transfer (`LicenseTransfers`) at or before that day never counts. The summary of all plugins sums the
    new customers of each plugin. `new-licenses` (`newLicenseDays`) counts a license at its first purchase, if it's
    paid: renewals, period changes, free licenses paid later and the purchases after a transfer don't count.
    `new-trial-users` counts the first trial of each customer (only for single plugins).
  Columns of chartable tables without a metric, e.g. the renewal rates and the cohorts, and the pie charts still show
  the values of their tables (`data-chart-value`, `data-chart-history`, `data-chart-share`).
- Vertical lines in line charts: major releases of the plugin (`MajorReleases`, from the plugin's releases), changes of the
  annual base price (`PriceChanges`, derived from undiscounted new US sales, the API has no price history) and major
  releases of IntelliJ IDEA (`IdeReleaseProvider`, from the JetBrains product service, cached for a day), and custom
  events of a YAML file (`--events`, `CustomEventsFile`, read again when it's modified). Each custom event belongs to a
  plugin (`plugin`), a page only contains the events of its plugin, the summary of all plugins the events of the paid
  plugins. The menu "Events" of the dialog lists the custom events (selected one by one, stored as `shownEvents`), a
  separator and the kinds of lines of the page (stored as `shownMarkers`), see `eventMenuItems` in `model.js`.
- The maximized chart dialog (the default) fills the window without a gap or border, its line charts share the
  height of the window (`chartHeight`, corrected by `fitChartHeights` in `dialog.js`). It doesn't use the Fullscreen
  API. On screens up to 600px wide, the chart dialog always fills the window and hides its hint, the button to maximize it and the
  lines of the dates one month and one year before and after the cursor. The button to close the dialog is
  the × of `removeButton` (`dialog.js`), which also removes a chart.
- Only series of the same period (`DataTable.chartPeriod`: day, month or year) share a line chart and its date range,
  the series of metrics have their own period `metric`.
  Values of months and years are located at the end of the period, the current period at today (`ChartValues.xValue`).
  The ticks of yearly values are at the values, the end of each year and today (`yearEndTicks` in `model.js`), so that a tick and the tooltip of its value show the same year. The date axis
  ends with the period of the last value (`periodEnd`), so that the value of the current period at today isn't at
  the right edge. Values of the first period of a plugin, which started within it, are partial (`partialUntil` of
  `/chart-data`, `isPartialPeriod` of the tables): hollow points and outlined bars in the color of the series.
- The menu "Range" of the dialog selects the shown dates of all line charts: all dates, a range until today (today,
  the past 7 days, this month, `RANGE_PRESETS` and `rangeOfPreset`, stored with their `preset` and calculated again
  when the setup is loaded) or a year with values (`rangeYears`, below the heading "Years", the latest year first,
  `rangeMenuItems`; while values of metrics are loading, the menu keeps the years of the previous values). Dragging over a line chart zooms in (`onZoom` in `lineChart.js`), on touch devices two fingers zoom
  in and out (`enablePinchZoom`, `pinchedRange` in `model.js`). The range is part of the stored
  setup (`dateRange`, ISO dates). Only the x-axis is limited (`visibleRange`), all values are still used, e.g. by the
  value table and the averages. The y-axis, the minimum and maximum and the latest values use the shown dates.
  With all dates, the x-axis covers the dates of the shown lines (`shownRange` in `lineChart.js`, `shownDateRange`
  in `model.js`): hiding a line, whose values start earlier, e.g. next to a 12-month average, shortens the axis.
- Series and averages, which were hidden by a click on the legend or the value table, stay hidden when the charts are
  drawn again, e.g. after changing the level (`hiddenSeries` of the setup, keys `<series>` and `<series>:<kind>`).
- The chart setup (columns, colors, options) is stored in the browser's local storage (version 2, other versions are
  ignored). Each plugin and the summary of all plugins have their own setup (`setupStorageKey` in `storage.js`:
  `marketplace-stats.charts.plugin-<id>` and `marketplace-stats.charts.all-plugins`), the plugin of a page is
  `data-plugin-id` of the `<body>` (`main.kte`, the plugin list has none). All pages of a plugin share its setup: the
  series of metrics are restored on all its pages, the other series and the pie charts on its pages with the same
  tables. Values are never stored. "Remove all charts" (the trash icon) resets the whole setup of the plugin
  (`emptySetup`), e.g. the styles, the levels and the range, the setups of other plugins are unchanged. The setup of
  `marketplace-stats.charts`, which all plugins shared before, is ignored.
- "Bookmark this view" of the Export menu writes the setup into the fragment of the address (`#charts=<base64url JSON>`,
  `encodeView`/`decodeView` in `storage.js`) and copies the address. Opening it replaces the stored setup of the
  plugin of the page with the view, opens the dialog and removes the fragment. Changing the charts also removes it.
- "Copy data URL" of the Export menu of a chart of metrics copies the address of `/chart-export` (`ChartExport.kt`),
  which returns the values of the shown lines, e.g. for an AI agent (`chartExportUrl` and `exportLineId` in
  `model.js`, `copyDataUrl` in `chartExports.js`; without access to the clipboard the message shows the address). One address
  describes the chart: `lines=<metric>[@avg<months>][@previous-year][:<window>],...` (the shown windows, hidden lines
  and trends are left out, the previous year is a line of its own), `detail`, `from`/`to` of the range,
  `incomplete=false` if incomplete values are hidden. The server derives the finer dates of short windows like the
  charts (`ChartExport.lineDetail`, `lineDetail` in `model.js`). The default format is CSV (one row per date, headers
  with label and unit, a `status` column for incomplete and partial values), `format=json` adds the metadata of each
  line. Charts of tables without a metric and pie charts have no address.
- uPlot isn't committed. Gradle downloads the npm package (`npm:uplot:<version>@tgz` in `build.gradle.kts`, from an
  Ivy repository for the npm registry) and the task `bundleJavascriptLibraries` extracts it into
  `build/generated/javascript/js`, which is added to the resources and served at `/js/`. To upgrade, change the version.
- Chart images on the server (`src/main/kotlin/dev/ja/marketplace/chartImage/`, the MCP tool `create_chart`): no
  charting library, `ChartImageRenderer` draws a `ChartImage` with the shapes of `java.awt.geom` on a `ChartCanvas`,
  which writes SVG (`SvgChartCanvas`) or draws with Java2D into a `BufferedImage` for PNG (`Graphics2DChartCanvas`),
  so both formats share the code. It ports the rules of the web charts (`alignedRanges`, `incompleteColor`,
  `lineDetail`, the colors and dashes of `CHART_STYLE`, the light and the dark theme of `CHART_THEMES` as
  `ChartTheme`), keep both in sync when the look of the web charts changes. `create_chart` has its own `theme`
  (light by default), independent of the theme of the web pages. The texts are measured with the AWT font `SansSerif`, PNG needs fonts and fontconfig (the
  `eclipse-temurin` image has DejaVu), without fonts the widths are estimated. The server runs headless
  (`java.awt.headless`).
- Tables opt in with `DataTable.isChartable` (line charts over the date in the first column) or columns with
  `DataTableColumn.isShareOfTotal` (pie charts with one slice per row) or `DataTableColumn.rowHistoryPeriod` (line
  charts with one series per row, e.g. the share of each customer type, with a metric for each row
  `<chartMetric>.<row>`, without a metric from `DataTableRow.getChartHistory`, written as `data-chart-history` JSON by
  `ChartValues.history`). The templates write the raw values and units of columns without a metric as `data-chart-*`
  attributes, computed by `ChartValues`, so the JavaScript doesn't parse formatted numbers.

## Styling of the Rendered HTML

- Pages are server-side rendered from JTE templates in `src/main/resources/templates/`. `main.kte` is the layout of the
  report pages; `dataTable.kte`, `dataTableSection.kte`, `dataRowGroup.kte` and `tableColumnsRow.kte` render the data tables.
- All styling is in `src/main/resources/styles/main.css`. The build minifies the stylesheets (`MinifyCss` in
  `build.gradle.kts`, task `minifyStylesheets`), the pages load `/styles/main.min.css` and `/styles/print.min.css`,
  the sources aren't part of the resources. The minifier only removes comments and whitespace, which isn't needed.
  `main.min.css` also contains the styles of the sortable tables and of uPlot (`appendedToMain`), so that the pages load
  a single stylesheet. It defines colors as CSS custom properties in `:root`. Use these properties instead of fixed colors.
- The font Lato is served by the server: Gradle downloads the npm package `@fontsource/lato` (an Ivy repository for the
  scope `@fontsource`), `bundleJavascriptLibraries` extracts the woff2 files of the subsets latin and latin-ext of the
  styles 400, 700 and 400 italic into `fonts/`. `fonts.kte`, included by all pages, writes the `@font-face` rules
  (`StaticAssets.fontFaceRules`) and preloads the files of the latin subset of the regular and bold text.
- Pages have a `<main>` element between the navigation and the footer, and a meta description. Columns without a title
  have an `accessibleTitle` (by default their chart title), which the header cell contains for screen readers
  (`visually-hidden`), so that the cells have a header.
- Dark mode: `:root[data-theme="dark"]` (within `@media screen`) redefines the custom properties. The theme is chosen
  in the footer of all pages (Auto, Light, Dark, `siteFooter.kte`), stored in the local storage of the browser
  (`marketplace-stats.theme`, without it "Auto"), and set as `data-theme` of `<html>` by `themeScript.kte` in the
  `<head>` of all pages, before they're drawn. "Auto" follows the color scheme of the browser, also when it changes.
  Changes dispatch the event `marketplace-themechange`, which draws the charts again (`preferredChartTheme`). Without
  JavaScript, the pages are light. Printing always uses the light colors. Text colors need a contrast of at least 4.5:1 on the page, the tables, their stripes and the hovered rows
  in both modes.
- `dataTable.kte` wraps each table in a `.table-card`. The cards of `.tables` flow in rows: narrow tables share a row,
  tables with `tables-row` or `table-column-wide` use a full row, wide tables scroll horizontally with a sticky first
  column.
- The sortable tables use `sortable.min.js` and `sortable-base.min.css` of the npm package `sortable-tablesort`. The
  base CSS only contains the arrows of the sorting, `sortable.min.css` would also style the tables. Like uPlot,
  they aren't committed: Gradle downloads the package and `bundleJavascriptLibraries` extracts the files, served at
  `/js/`. To upgrade, change the version in `build.gradle.kts`.
- `src/main/resources/styles/print.css` contains the print-only rules (`.noprint`, `.noprintlink`, A4 landscape `@page`).
  Templates link `print.min.css` with `media="print"`.
- The cells of the tables are written with `HtmlAttributes.of` (`dataTableSection.kte`): only attributes with a value,
  without the spaces, which JTE keeps for omitted attributes. The lists can have tens of thousands of rows, e.g. the
  licenses: keep the markup of the cells small, e.g. the links of IDs have no class (`print.css` hides them by their
  target), the symbol of expired licenses is part of the CSS. Tables with at least 50 rows have the classes of their
  columns as rules of the table (`CellStyles`, a `<style>` and `data-cells` of the table, `:nth-child` of the cells),
  the cells have no classes: new classes of cells need their declarations in `CellStyles`, unknown classes stay classes
  of the cells. The links in the cells have the short parameter `pId` instead of `pluginId`, the pages accept both. Numbers in texts, e.g. footers and tooltips, are
  formatted with `Formatters.formatInteger` and `Formatters.formatAmount` (the format of `Formatters.MonetaryAmount`,
  cached by the amount, the format of Moneta allocates much memory for each call).
- The rows of tables with more than 1,000 rows (`TableChunks`, a single section, with the rules of `CellStyles`, not
  chartable, without collapsed rows) are split into tables of 500 rows in `.table-chunk` elements of the card
  (`dataTable.kte`, `dataTableSection.kte`). They have `content-visibility: auto`: the browser doesn't lay out and
  paint them while they're off-screen. A single table of tens of thousands of rows is laid out again as a whole after
  each part of the page and needs much memory in the browser. The first table has the header, the first rows and `data-row-chunks`, the last
  table the footer. `chunkedTables.js` adds a collapsed row with the longest texts of the columns to the header, gives
  the other tables the widths of the columns of the first table (`table-layout: fixed`, also after resizing) and their
  estimated heights, and moves all rows into the first table while `sortable.min.js` sorts it (`sort-start`,
  `sort-end`). Without JavaScript, each table has its own widths of the columns.
- Table styling is partly set from Kotlin: `DataTable` subclasses in `marketplace-data` pass CSS class names (for example
  `cssClass = "small table-striped"`) and per-column `cssClass`/`cssStyle` values (`DataTable.kt`). These classes must exist in `main.css`.
- Static resources are served by `staticAssets("/styles", "styles")`, `staticAssets("/js", "js")`,
  `staticAssets("/favicon", "favicon")` and `staticAssets("/fonts", "fonts")` in `MarketplaceStatsServer.kt` (`StaticAssets.kt`): the ETag is the SHA-256 of
  the content, the plugin ConditionalHeaders answers with 304. Templates link static files with
  `${dev.ja.marketplace.StaticAssets.url("/js/charts.min.js")}`, which adds the hash of the content (`?v=<hash>`).
  These addresses are cached for a year (`immutable`), a new version has a new address. Requests without the hash
  are validated with the ETag (`no-cache`).

## Conventions

- Every source file starts with the copyright/SPDX license header used in existing files
  (`Copyright (c) <years> Joachim Ansorg.` / `SPDX-License-Identifier: AGPL-3.0-or-later`).
- Kotlin official code style (`kotlin.code.style=official`).
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `chore:`).
- New JetBrains product codes are registered in `marketplace-client/.../services/JetBrainsProductCode.kt`; unknown codes must not break at runtime.
- Monetary amounts must keep their currency; use the existing Moneta-based helpers instead of raw `Double`/`BigDecimal` arithmetic.
- Tests, which call suspending code, are suspending functions (`@Test suspend fun x() { ... }`, supported by JUnit 6),
  not `fun x() = runBlocking { ... }`. JUnit needs `kotlin-reflect` for them, which is on the test runtime classpath of
  all projects. A test, which needs a `CoroutineScope`, e.g. for `async`, uses `= coroutineScope { ... }`.

## Releases

The version is stored in `VERSION.txt` and exposed via the generated `BuildConfig.APP_VERSION`.
`deploy.bash` builds, publishes the libraries to maven.j-a.dev, tags, creates the GitHub release and pushes Docker
images — only run it when explicitly asked.

## Publishing the Libraries

- `marketplace-client` and `marketplace-data` are published with `maven-publish` as
  `dev.j-a.jetbrains-marketplace:marketplace-client` and `dev.j-a.jetbrains-marketplace:marketplace-data`, with the
  version of `VERSION.txt` (the block `configure(listOf(project(":marketplace-client"), ...))` in `build.gradle.kts`,
  like the setup of jetbrains-dap). The root project, the web application, isn't published.
- Each publication has the JAR, a sources JAR, an empty javadoc JAR (no Dokka), the POM (AGPL-3.0-or-later, developer,
  SCM) and the Gradle module metadata. The bytecode targets Java 25, so users need Java 25 or later.
- The repository `jaDev` of the build is the self-hosted Reposilite, which isn't public: `https://maven.j-a.dev/releases`, or
  `https://maven.j-a.dev/snapshots` for a `-SNAPSHOT` version. Its credentials are the Gradle properties `jaDevUsername`
  and `jaDevPassword` (the name and the secret of an access token, e.g. `ORG_GRADLE_PROJECT_jaDevUsername`). Never
  print or commit them. Publish with `./gradlew publishAllPublicationsToJaDevRepository --no-configuration-cache`
  (part of `deploy.bash`), the same version can't be published twice. Only publish when explicitly asked, test with
  `./gradlew publishToMavenLocal` (`-Dmaven.repo.local=<dir>` writes to another directory than `~/.m2`).
- The POMs list the dependencies of the modules: `api` dependencies with scope compile, `implementation` with scope
  runtime. Types of a library, which are used in the public API of a module (e.g. kotlinx-datetime, Moneta,
  fastutil, Ktor, the classes of `marketplace-client` in `marketplace-data`), must be `api` dependencies of the modules,
  otherwise users can't compile against the module. The shared dependencies of `allprojects` are `api` for the modules
  (`exposedDependency`), but `implementation` for the root project. Dependencies, which only the web application needs
  (Logback, Clikt, the Ktor server, JTE, the MCP SDK), belong to the root project and must not be added to the modules.
- The dependencies of the modules must be resolvable from Maven Central. The Ivy repository of the npm packages is
  only defined for the root project and must not be used by the modules.
- Shadow's `minimize` keeps all classes of the `api` dependencies of the root project, also those of its `api` project
  dependencies. After changing the dependencies of the modules, compare the files of the fat JAR with
  `./gradlew shadowJar --no-build-cache --rerun-tasks`.
