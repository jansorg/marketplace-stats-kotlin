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
- fastutil for collections on hot paths (per-license sets and maps), Caffeine for caching, Logback for logging
- JUnit 6 for tests
- Gradle plugins: Shadow (fat JAR), gmazzo BuildConfig (`BuildConfig.APP_VERSION` from `VERSION.txt`)

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
added to the models as optional properties at the end of the constructors, unknown enum values as strings.

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
  `/pricing`, `/downloads/monthly`, `/chart-series` (JSON), all with `?pluginId=<id>`. `POST /refresh?pluginId=<id>&reload=true` drops cached data.
  See the routing in `MarketplaceStatsServer.kt`.
- The MCP server is served at `/mcp` (Streamable HTTP, `src/main/kotlin/dev/ja/marketplace/mcp/`, see README.md).
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
  a day) and the products and releases of JetBrains (`CachingJetBrainsServiceClient`, in `jetbrains/`, shared by all
  accounts, fetched again after a day).
  The data of an account is stored in `accounts/account-<hash of API host and key>/`.
- The stored classes (`CachedSalesModel.kt`, `CachedEcbRates`) are separate from the API model. After incompatible
  changes, increment the data version (`HistoryKind`, `DATA_VERSION`). `CachedSalesModelCoverageTest` fails if a
  property of the API model isn't stored.
- The cache contains real sales data, delete test caches after use.

## Charts

- The charts are ES modules in `src/main/resources/js/charts/`, loaded without a build step (`main.js` is the entry
  point). Line charts use uPlot, pie charts are SVG. Keep logic out of the DOM code:
  - `format.js`, `model.js`, `storage.js`: pure functions, tested with Node.js in `src/test/js/*.test.mjs`
    (`node --test src/test/js/`, or `./gradlew testJavascript`, which is part of `check` and skipped without Node.js)
  - `tableData.js`: reads the `data-chart-*` attributes of the tables
  - `lineChart.js`, `pieChart.js`, `dialog.js`, `menu.js` (dropdown menus): rendering, `main.js`: state of the charts
  - `exportImage.js`: images of the charts with title and legend, for downloads (PNG, pie charts also SVG) and
    printing. Printing adds the images to the page and `print.css` hides everything else (`body.print-charts`).
- The key C shows and hides the chart dialog.
- The cursor of a line chart snaps to the closest value. Dashed and dotted lines mark the dates one year and one month
  before and after it, the table below the chart compares the values at these dates (`enableComparisons` in
  `lineChart.js`). The table replaces the uPlot legend, a click on the label of a series shows or hides it. Without
  the option "Value table" of the View menu, the uPlot legend is shown instead, it only shows and hides the series.
- Columns declare what they measure with `DataTableColumn.chartMeasure` (`ChartMeasure`: flow, stock or ratio), written
  as `data-chart-measure`. The trends of the View menu (3- and 12-month moving averages, previous year) are only added
  to suitable series (`overlayApplies` in `model.js`): averages to monthly values with the granularity Month, because
  the values per week or day are already sums of the month before each day. Options without effect on the shown
  charts are dimmed in the menu.
- Incomplete values are drawn as dotted lines, hollow points and striped bars in a lighter color of their series
  (`incompleteColor` in `model.js`, darker in the dark mode).
- Colors of the dark mode: `CHART_THEMES` in `lineChart.js` (axes, grid, surface, vertical lines) and
  `DARK_SERIES_COLORS` in `model.js`. The stored chart setup always uses the light series colors, `displayColor` maps
  them when the charts are drawn. Exported and printed images always use the light colors, a dark chart is drawn again
  with the light colors for the image.
  The option "Incomplete values" of the View menu of the chart dialog hides them, "Minimum" and "Maximum" mark the
  lowest and highest complete value of each series.
  "Only averages" of the View menu hides the values of series with a shown moving average (`averagesOnly`, the
  series aren't drawn, but their values are in the value table). Series without an average are always drawn.
- In sortable tables (`sortable` CSS class), a click on the label of a column header opens the chart and a click on
  the rest of the header, e.g. the arrow, sorts the table.
- Line charts of the overview table can show the values per week or per day. Columns with values per day set
  `DataTableColumn.dailyChartSeries`, the charts load the values from `/chart-series?pluginId=<id>&granularity=day|week`
  (`DailyChartSeries`, calculated by `OverviewDailyValues`). Sums, e.g. of sales, are the sums of the month before
  each day, licenses, MRR and ARR the values at each day, so that the last day of a month has the value of the table.
- Vertical lines in line charts: major releases of the plugin (`MajorReleases`, from the plugin's releases), changes of the
  annual base price (`PriceChanges`, derived from undiscounted new US sales, the API has no price history) and major
  releases of IntelliJ IDEA (`IdeReleaseProvider`, from the JetBrains product service, cached for a day), and custom
  events of a YAML file (`--events`, `CustomEventsFile`, read again when it's modified), which the user selects one by
  one in the menu "Events" of the dialog (stored as `shownEvents`).
- Only series of the same period (`DataTable.chartPeriod`: day, month or year) share a line chart and its date range.
  Values of months and years are located at the end of the period, the current period at today (`ChartValues.xValue`).
  The ticks of yearly values are at the end of each year and at today (`yearEndTicks` in `model.js`). Line charts of
  yearly tables can also show the sums of the year before the end of each month (granularity `monthEnd`,
  `/chart-series?granularity=month`).
- The chart setup (columns, colors, options) is stored in the browser's local storage (`marketplace-stats.charts`) and
  restored on all pages with the same tables. Values are never stored, they're read from the tables of the current page.
- "Bookmark this view" of the Export menu writes the setup into the fragment of the address (`#charts=<base64url JSON>`,
  `encodeView`/`decodeView` in `storage.js`) and copies the address. Opening it replaces the stored setup with the
  view, opens the dialog and removes the fragment. Changing the charts also removes it.
- uPlot isn't committed. Gradle downloads the npm package (`npm:uplot:<version>@tgz` in `build.gradle.kts`, from an
  Ivy repository for the npm registry) and the task `bundleJavascriptLibraries` extracts it into
  `build/generated/javascript/js`, which is added to the resources and served at `/js/`. To upgrade, change the version.
- Tables opt in with `DataTable.isChartable` (line charts over the date in the first column) or columns with
  `DataTableColumn.isShareOfTotal` (pie charts with one slice per row) or `DataTableColumn.rowHistoryPeriod` (line
  charts with one series per row, e.g. the share of each customer type per month, from `DataTableRow.getChartHistory`,
  written as `data-chart-history` JSON by `ChartValues.history`). With `dailyChartSeries`, the series of a row also have
  values per day and per year from `/chart-series` (`<id>.month.<row>` and `<id>.year.<row>`, e.g.
  `CustomerTypeDailyValues`), the chart then offers Year (granularity `yearEnd`, `/chart-series?granularity=year`),
  Month, Week and Day. The templates write the raw values and units as
  `data-chart-*` attributes, computed by `ChartValues`, so the JavaScript doesn't parse formatted numbers.

## Styling of the Rendered HTML

- Pages are server-side rendered from JTE templates in `src/main/resources/templates/`. `main.kte` is the layout of the
  report pages; `dataTable.kte`, `dataTableSection.kte`, `dataRowGroup.kte` and `tableColumnsRow.kte` render the data tables.
- All styling is in `src/main/resources/styles/main.css`, served at `/styles/main.css`. It defines colors as CSS custom
  properties in `:root` and loads the Lato font from Google Fonts. Use these properties instead of fixed colors.
- Dark mode: `@media screen and (prefers-color-scheme: dark)` redefines the custom properties. Printing always uses the
  light colors. Text colors need a contrast of at least 4.5:1 on the page, the tables, their stripes and the hovered rows
  in both modes.
- `dataTable.kte` wraps each table in a `.table-card`. The cards of `.tables` flow in rows: narrow tables share a row,
  tables with `tables-row` or `table-column-wide` use a full row, wide tables scroll horizontally with a sticky first
  column.
- The sortable tables use `sortable.min.js` and `sortable-base.min.css` of the npm package `sortable-tablesort`. The
  base CSS only contains the arrows of the sorting, `sortable.min.css` would also style the tables. Like uPlot,
  they aren't committed: Gradle downloads the package and `bundleJavascriptLibraries` extracts the files, served at
  `/js/`. To upgrade, change the version in `build.gradle.kts`.
- `src/main/resources/styles/print.css` contains the print-only rules (`.noprint`, `.noprintlink`, A4 landscape `@page`).
  Templates link it with `media="print"`.
- Table styling is partly set from Kotlin: `DataTable` subclasses in `marketplace-data` pass CSS class names (for example
  `cssClass = "small table-striped"`) and per-column `cssClass`/`cssStyle` values (`DataTable.kt`). These classes must exist in `main.css`.
- Static resources are served by `staticResources("/styles", "styles")` and `staticResources("/js", "js")` in
  `MarketplaceStatsServer.kt`.

## Conventions

- Every source file starts with the copyright/SPDX license header used in existing files
  (`Copyright (c) <years> Joachim Ansorg.` / `SPDX-License-Identifier: AGPL-3.0-or-later`).
- Kotlin official code style (`kotlin.code.style=official`).
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `chore:`).
- New JetBrains product codes are registered in `marketplace-client/.../services/JetBrainsProductCode.kt`; unknown codes must not break at runtime.
- Monetary amounts must keep their currency; use the existing Moneta-based helpers instead of raw `Double`/`BigDecimal` arithmetic.

## Releases

The version is stored in `VERSION.txt` and exposed via the generated `BuildConfig.APP_VERSION`.
`deploy.bash` builds, tags, creates the GitHub release and pushes Docker images — only run it when explicitly asked.
