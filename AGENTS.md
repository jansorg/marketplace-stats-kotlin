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
| `marketplace-client/` | Ktor-based HTTP client for the Marketplace API (`client/`), caching wrapper (`CaffeineCacheMarketplaceClient`), API models (`client/model/`), JetBrains product/release services (`services/`), currency handling and exchange rates (`exchangeRate/`). |
| `marketplace-data/`   | Aggregation and analysis of marketplace data: report tables in `data/*` (overview, customers, licenses, trials, churn, funnel, countries, ...), trackers, formatting helpers.                                                                    |
| root project (`src/`) | Ktor/Netty web server and CLI entry point (`dev.ja.marketplace.ApplicationKt`), page definitions, JTE templates (`src/main/resources/templates/*.kte`), CSS and JS assets.                                                                        |

Dependency direction: root → `marketplace-data` → `marketplace-client`. All Kotlin sources live in package `dev.ja.marketplace`.

## Tech Stack

- Gradle 9.8 with the Kotlin DSL and configuration cache enabled
- Kotlin 2.3.21 (JVM plugin and serialization plugin)
- Java: built and run with JDK 27 (Gradle toolchain, CI, Docker image `eclipse-temurin:27-jre`). The bytecode targets
  Java 25 because Kotlin 2.3 can't emit JVM 27 bytecode. No toolchain download repository is configured, so a JDK 27
  must be installed locally; Gradle detects it automatically. The default `java` on the PATH can be an older version.
- Ktor 3.6 (client with the Java engine, server with Netty), kotlinx-serialization 1.11, kotlinx-coroutines 1.11,
  kotlinx-datetime 0.8 (the `0.6.x-compat` variant, which Ktor requires)
- JTE 3.2.4 templates (`.kte`, Kotlin syntax), precompiled at build time
- Clikt 5 for the CLI (`Application.kt`)
- Moneta (JSR 354) for monetary amounts and currency conversion, Caffeine for caching, Logback for logging
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

  Before upgrading a Kotlin library, check the `kotlin-stdlib` version it requires, and afterwards check
  `./gradlew dependencyInsight --configuration runtimeClasspath --dependency org.jetbrains.kotlin:kotlin-stdlib`.
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

## Running the Web Server

```bash
./gradlew precompileJte run --args="-d normal --host 127.0.0.1 --port 18080 config.json"
```

- `config.json` (git-ignored) holds the API key. Its supported keys are `apiKey`, `displayedCurrency` and
  `showResellerCharges` (see `ApplicationConfig.kt`). The host and port can't be set in the config file. They come from
  `--host`/`--port` or `MARKETPLACE_SERVER_HOSTNAME`/`MARKETPLACE_SERVER_PORT`, and default to `0.0.0.0:8080`.
- Pass `--port` explicitly and use a port other than 8080, so the server doesn't collide with an instance the user
  is already running. Bind to `127.0.0.1`.
- `-h` is `--host`, not help. Use `--help` for usage. `-d none|normal|verbose` sets the log level.
- The server blocks the terminal. Start it in the background, wait for the line `Launching web server: http://<host>:<port>/`,
  and stop it when you're done.
- Before printing that line, the server loads the plugin list and the countries from the JetBrains APIs. Plugin data is
  loaded lazily on the first request for a plugin and then cached in memory, so the first page load for a plugin is slow.
  For testing, prefer a plugin with few sales, because plugins with a long sales history take much longer to load.
- Useful routes: `/` (plugin list, or report with `?pluginId=<id>`), `/licenses`, `/customers`, `/trials`, `/countries`,
  `/pricing`, `/downloads/monthly`, all with `?pluginId=<id>`. `POST /refresh?pluginId=<id>&reload=true` drops cached data.
  See the routing in `MarketplaceStatsServer.kt`.
- A JetBrains Marketplace API key is required. Besides the config file, it can be passed via `--api-key` or the
  `MARKETPLACE_API_KEY` environment variable.
- `config.json`, `config.*.json` and `.env` are git-ignored and may contain real API keys. Never commit them or print their contents.
- See `README.md` for all CLI options and environment variables.

## Styling of the Rendered HTML

- Pages are server-side rendered from JTE templates in `src/main/resources/templates/`. `main.kte` is the layout of the
  report pages; `dataTable.kte`, `dataTableSection.kte`, `dataRowGroup.kte` and `tableColumnsRow.kte` render the data tables.
- All styling is in `src/main/resources/styles/main.css`, served at `/styles/main.css`. It defines colors as CSS custom
  properties in `:root` and loads the Lato font from Google Fonts.
- `src/main/resources/js/sortable.css` and `sortable.min.js` provide the sortable tables (third-party, don't edit).
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
