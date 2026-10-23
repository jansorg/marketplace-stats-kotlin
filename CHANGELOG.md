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
- Fix sorting of licenses by validity, which compared a license with itself.
- Count only paid new licenses as converted trials, not renewals or free licenses.
- Use the same trial conversion rate everywhere: purchases within the trial period and 30 days after it (`Marketplace.TRIAL_CONVERSION_EXTRA_DAYS`).
- Mark trial conversion rates as incomplete while trials can still convert.
- Show the MRR and ARR of the current month and year as incomplete values instead of hiding them.
- Show the churn rates of the current month and year as incomplete values, calculated until today.
- Limit renewals, which prevent churn, to renewals within 90 days after the license expired (`Marketplace.LICENSE_RENEWAL_GRACE_DAYS`).
- Show a churn rate of 0% instead of "—" if there were active licenses.
- Fix the tooltip of the trial conversion rate of the trial countries table, which used the number of trials as the number of days.
- Fix amounts below 1 rendered without the leading zero in English locales, e.g. "USD .50".
- Fix sorting of amounts which only differ in cents.
- Round amounts with more than 5 decimal places instead of failing to load the sales of a plugin.
- Cache exchange rates only for dates before the latest published ECB rates.
- Show columns of the tables with dates as line charts, and share columns like "% of Sales" as pie charts, by clicking the column header.
- Remember the charts in the browser and restore them on later visits, available via the "Charts" link in the navigation.
- Show a tooltip with the values of all lines at the cursor, switch line charts between lines and bars, and show only values of the same period in one chart.
- Mark changes of the base price and major releases of IntelliJ IDEA in line charts, with toggles for each kind of vertical line.
- Add `AGENTS.md` with setup information for coding agents.
