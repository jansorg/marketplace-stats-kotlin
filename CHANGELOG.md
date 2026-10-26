# Changelog

## 0.46.0

- Charts:
  - The values of the charts are calculated by the server for each day (`/chart-data`, `/chart-metrics`), with the
    rules of the tables. Each line has its own window (e.g. per day, last 7 days, last 12 months, calendar month or year)
    and the chart a detail level (year, month, week, day).
  - The gear of a line in the legend selects its window and whether it's drawn like the chart, as a line or as bars.
    The × removes a line.
  - The "+" menu adds metrics with their values or their 1-, 3- or 12-month moving averages. Moving averages are
    always lines, dashed next to the values.
  - The titles of the lines describe their values, e.g. "Paid amount" instead of "Invoice".
  - Two y-axes have their ticks at the same heights, an axis without shown lines is hidden.
  - The ticks of yearly charts are at the values, the tooltip shows the date of each value in the locale of the server.
  - Bars at all levels, partial values of the first period of a plugin, the date axis ends with the current period.
  - View options for the comparison lines and for hiding the values of the axes, the image of a chart can be copied.
  - Menus work with touch screens.
- The tables of subscriptions measure the current month at today, like the overview.
- The footer shows the date and the time of the page.
- The JavaScript and the stylesheets are minified. Lato is loaded in parallel to the styles, Noto Sans SC isn't
  loaded anymore.

## 0.45.1

- Charts on small screens: the dialog has no button to maximize it, it always fills the window, and the lines one month
  and one year before and after the cursor are hidden.
- A chart, which is already shown, opens without the message "... is already shown".

## 0.45.0

- Charts:
  - The menu "Events" contains all vertical lines: the custom events of the plugin, then the releases of the plugin, the
    price changes and the releases of IntelliJ IDEA. The summary of all plugins shows the events of the paid plugins.
  - All columns of the monthly tables show the values per year, e.g. MRR, ARR, sales, downloads and the subscription
    metrics. The converted trials show the values per year, month, week and day.
  - "Only averages": the legends and the value table list the averages instead of the hidden values, the averages are
    drawn solid, their minimum and maximum are marked. Several averages of a value have different shades of its color.
  - A click on a value or on one of its moving averages in the legend only shows or hides this line. Hidden lines stay
    hidden after changing the level of a chart and are part of bookmarked views.
  - The dialog fills small screens with less padding, closes with a ×, removes all charts with an icon button and shows
    the name of the plugin. Pie charts are larger on large screens, the selected level and style are clearly visible
    in the dark mode.
- Marketplace client:
  - The licenses of a sale add up to the amount of the sale, also of refunds with several line items.
  - Values, which the Marketplace adds later, e.g. new types of sales, customers, license periods, product families or
    currencies, don't fail the data.
  - Failed requests aren't cached for days, requests have timeouts and retry too many requests.
  - Today is the day in the time zone of the Marketplace, e.g. the sales of the new day are loaded in the Docker image.
  - Outdated exchange rates are used while they're updated, reports in other currencies than USD don't wait for or fail
    without the ECB.
  - Sales of the recent 45 days are fetched again, also at the end of a month. Data read from disk isn't kept longer
    than intended, unchanged fetches of the recent sales don't write the history again.
  - The sales reports support perpetual licenses and skip invalid rows instead of failing.
  - New endpoints: the organizations of the account, the Check License API, the ratings of the categories of the
    reviews, the results of the plugin verifier and the end date of the downloads. All pages of the plugins of the
    account are loaded.
- `marketplace-client` and `marketplace-data` are published as Maven libraries (`dev.j-a.jetbrains-marketplace`).

## 0.44.0

- Charts:
  - Line charts can be zoomed in by dragging, on touch devices with two fingers. The menu "Range" shows all dates or
    a year. The range is stored with the chart setup and is part of bookmarked views.
  - "Bookmark this view" of the export menu writes the view of the charts into the address of the page.
  - Values per week and per day for the table of the years and the churn, the line charts of yearly tables show the
    sums of the year before the end of each month. The churn and the shares of the customer types also show the
    values per year.
  - The share of the sales of the customer types is a line chart of the share of each customer type per month.
  - Trends: a 1-month moving average of the values per week and per day, the moving averages are also shown per week
    and per day, "Only averages" shows only the averages of lines with an average.
  - Custom events of a YAML file as vertical lines (`--events`, `MARKETPLACE_EVENTS_FILE`, `eventsFile`), see
    `events-template.yml`.
  - The dialog is maximized by default, each chart can be removed with a button, the labels of the minimum and
    maximum only show the value in a larger font.
- `--auto-refresh` refreshes the data of the requested plugins in the background, so that the pages don't wait for
  the Marketplace. The durations of all caches are defined in one place.
- Perpetual licenses: the license tables show perpetual licenses, the page of a customer has a table of its perpetual
  licenses, the table of the customers supports them, the pricing page shows the perpetual prices.
- The pages of a sale, a license and a customer show the header of the plugin, the details are a grid. The licenses of
  a line item show the paid amount of each license and the totals.
- The tables Today and Yesterday only show the license periods offered for the plugin, the totals of their sections are
  bold. The top countries and top trial countries of the overview show 5 countries, the tables of the countries and of
  the trial funnel 20 rows and a row to show all.
- The button "Drop caches" is a small button with an icon in the footer.
- Fixes:
  - The MRR and ARR of the current month and year are the values of today, not of the end of the period.
  - The churn, the renewal rates and the license cohorts use the same grace period and count only renewal sales as
    renewals, free subscriptions aren't renewals.
  - The price of the third year of a subscription is the price of the third year.
  - The expected amounts of sales through resellers use the percentage of the discount of the reseller.
  - The rates of the subscription metrics are 0 % if nothing was retained, and have no value without anything to
    compare. The MRR movements per day count a customer as new in the same month as the table.
  - The table of the past 7 days shows 7 days and the amounts of the sales.
  - The footer of the customers counts a license of several customers once, the overview counts licenses with several
    sales once.
  - The shares of the sortable country tables are sorted by their exact values, the shares of the top trial countries
    include the trials without a country.
  - The pricing page doesn't fail for a currency without countries.
- Faster reports: fewer passes over the licenses and trials.
- The README has a clear structure and a table of all settings.

## 0.43.0

- Licenses churn, not customers: a license, which another customer renewed, e.g. after a transfer to an organization,
  isn't churned. The navigation has a menu "Licenses" with all, active and churned licenses. "Customers" is a single
  page with the total, active, transferred and churned licenses of each customer, the names link to the customers.
  The license tables of a customer show, which customer renewed a transferred license. `/customers/active` and
  `/customers/churned` redirect to the new pages.
- Fallback versions: for plugins with perpetual fallback licenses, the license tables of a customer and the license
  page show the version, which the fallback license covers, formatted like JetBrains, with its release date.
  Earlier versions than the latest version of the plugin are gray.
- The purchases of a license in the same sale are a single row of the license tables. The pages of active and churned
  licenses show one row per license, the row limit counts the shown licenses.
- Expired licenses are clearly gray and marked with a symbol.
- The page of countries shows the churn of annual and monthly licenses of each country in the last 12 months.
- The page of a sale shows all its details, e.g. the customer type and country, the reseller and a section for each
  line item with its subscription, amounts, paid amounts, discounts and licenses. Unknown refnums return 404.
- The cohort table of the subscription metrics has a shorter title and tooltips, the customer counts of the
  subscription tables have thousands separators. The tables of months show the last 12 months, the other months on
  demand.
- Sortable tables show faint arrows on all sortable columns, the current page in the navigation bar has a visible
  background.
- MCP server: `list_customers` returns the counts of churned and transferred licenses instead of a churn date, the
  status of a customer is active or inactive, the filters `churnedFrom` and `churnedTo` are replaced by `untilFrom`
  and `untilTo`. `list_reviews` returns the resolve state, sub-ratings and versions of reviews.
- The Marketplace client supports properties of the API responses, which it ignored before.
- Fix: the bundled JavaScript libraries were not updated after changes of their files, e.g. the arrows of the sortable
  tables were missing.

## 0.42.1

- Faster pages: the summary of all plugins on the list of plugins and its values per week and per day, and the
  recurring revenue of the customers of the subscription metrics are reused in the next requests, until the data or
  the day changes.
- Smaller HTML of the tables, the cells don't contain whitespace anymore, e.g. the licenses page is about half as large.
- Percentages are formatted with the cached number formats.

## 0.42.0

- The list of plugins shows a summary of all paid and freemium plugins: sales, fees, invoiced amounts, MRR, ARR and
  downloads by year, and the same values for each day of the current month. Both tables show their latest rows and the
  others on demand. The data of the plugins is loaded at the start, `--no-all-plugins-summary` disables the summary.
  The plugins are shown as cards.
- Report pages have a menu next to the name of the plugin to switch to another paid plugin or to the list of plugins.
- Charts per week and per day for the subscription metrics (MRR movements, revenue retention, average revenue per
  customer), the monthly downloads and the summary of all plugins. Moving averages are available per week and per day.
- The renewal rates of monthly licenses are shown by month.
- MCP server: a new tool `list_offers` lists the special offers and the number of their coupons, `list_plugins` returns
  the monetization settings of paid plugins, and `list_sales` marks line items of sales with a total of 0 as free
  licenses.
- The Marketplace client supports the requests for special offers, coupons, release dates, monetization settings and
  the sales reports.
- Favicons and a footer with the version and links to the project.
- Long table titles have their own row, so that they don't widen the first column.
- The sortable tables use sortable-tablesort 4.1.7, which is downloaded at build time.
- Fix scrolling of the page behind the chart dialog on iPadOS, closing of the chart dialog by a click on its padding,
  and the labels of the date axis of charts per week and per day.
- `docker-compose.yml` explains how to store the persistent cache in a directory of the host.

## 0.41.0

- Dark mode, which follows the setting of the system. Its colors are chosen for a contrast of at least 4.5:1 for all text.
  Charts have their own colors in dark mode, exported and printed charts stay light.
- Tables are shown as cards in a grid: narrow tables share a row, wide tables use a full row. Tables wider than the
  screen scroll horizontally with a fixed first column. The navigation is a sticky bar, which marks the current page.
- Typography: lighter headings, larger table text, column labels in a secondary color and bold totals.
- The chart dialog is usable on tablets: the page behind it doesn't scroll, maximized charts fit the screen, and the
  charts keep the size of the dialog after rotating the device or returning to the page.
- The MCP server at `/mcp` doesn't use sessions anymore. Clients don't need the `Mcp-Session-Id` header, and memory
  is no longer retained for each session.
- Faster rendering of the overview, subscription metrics, customers, countries and pricing pages with fewer allocations.

## 0.40.0

- Add an MCP server for AI agents at `/mcp` (Streamable HTTP), enabled by default. Its tools provide the report tables,
  sales, customers, licenses, trials, license cohorts and transfers, prices, price changes, exchange rates, downloads by IDE
  product and plugin version, reviews, visitors of the plugin page, and releases of the plugin and of JetBrains products.
  `--no-mcp` disables it, `--mcp-allowed-hosts` adds hostnames for remote access. See the README for the list of tools.
- Store sales, trials, prices, exchange rates, downloads and the products of JetBrains in a persistent cache with
  `--cache-dir`, so that restarts are fast. The Docker image enables it, `docker-compose.yml` keeps it in a volume.
- Add a subscription metrics page: MRR movements, net and gross revenue retention, renewal rates by license period and
  number of previous renewals, retention and realized lifetime value of yearly customer cohorts, and the average MRR per customer.
- Line charts: compare the values at the cursor with the previous and next month and year, show the overview table per week
  or per day, mark incomplete values, add moving averages and the previous year as trends, and add a View menu with options.
- Print the charts and export them as PNG or SVG images. The plugin logo opens the charts.
- Link to the list of all plugins on each page and in the page footer.
- The trials of the "Today" and "Yesterday" tables have one row per country.
- Fix sorting of sortable tables with a click on the arrow of a column header.
- Fix the trial countries page failing for trials without a country.
- Fix the label of the paid licenses in charts.
- Enable native access for Netty to avoid the JVM warning.
- Remove the Guava dependency and unused classes from the fat JAR.
- Download the JDK of the Gradle toolchain if it isn't installed.

## 0.30.0

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
- Remember the charts in the browser and restore them on later visits, available via a click on the plugin logo.
- Show a tooltip with the values of all lines at the cursor, switch line charts between lines and bars, and show only values of the same period in one chart.
- Mark changes of the base price and major releases of IntelliJ IDEA in line charts, with toggles for each kind of vertical line.
- Add `AGENTS.md` with setup information for coding agents.
