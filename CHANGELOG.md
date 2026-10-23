# Changelog

## Unreleased

- Upgrade to Java 27 for builds, CI and the Docker image; the bytecode targets Java 25.
- Upgrade Gradle to 9.8.2.
- Upgrade Ktor to 3.6.0, JTE to 3.2.4, Kotlin to 2.3.21 and JUnit to 6.1.3.
- Upgrade Clikt to 5.1.0 and the other dependencies to their latest versions compatible with Kotlin 2.3.
- Fix the print stylesheet: printed pages now use `print.css` instead of `main.css`.
- Improve typography: render pages in standards mode, use accessible text colors, load only the used font weights and keep navigation links on one line.
- Render the plugin overview about 4x faster by using hash sets for churn tracking and removing unused trackers.
- Use fastutil collections for per-license maps and sets.
- Load the monthly sales and trials chunks with up to 4 concurrent requests to speed up the first load of a plugin.
- Load ECB exchange rates lazily with a lightweight loader instead of Moneta, reducing memory usage by about 80 MB.
- Use the exchange rate of the closest day with published ECB rates, e.g. the following Monday for Sundays.
- Fix MRR and ARR: discounts other than the continuity discount were applied twice.
- Fix the overview page failing with "Unable to find base price" when assertions are enabled, e.g. with `./gradlew run`.
- Convert MRR and ARR to the display currency at the sale date instead of today's rate for future license end dates.
- Add `AGENTS.md` with setup information for coding agents.
