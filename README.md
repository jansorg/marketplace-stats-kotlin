# JetBrains Marketplace Statistics

Marketplace Stats shows sales and usage statistics for paid plugins on the
[JetBrains Marketplace](https://plugins.jetbrains.com/).
It is a web application: you start it on your computer or server and open the reports in your browser.

Features:

- Reports of sales, licenses, customers, trials, countries, subscriptions, resellers and prices
- Charts of the table values, with trends, vertical lines for releases and your own events
- A summary of all your paid plugins
- An optional cache on disk, so that restarts are fast
- An optional refresh in the background, so that the pages don't wait for the Marketplace
- An [MCP](https://modelcontextprotocol.io/) server, so that AI agents can analyze your data

This is a new version of [marketplace-stats](https://github.com/jansorg/marketplace-stats). It is written in Kotlin
instead of Go.

## Contents

- [Quick Start](#quick-start)
- [Requirements](#requirements)
- [Configuration](#configuration)
- [Persistent Cache](#persistent-cache)
- [Automatic Refresh](#automatic-refresh)
- [Custom Events in the Charts](#custom-events-in-the-charts)
- [MCP Server for AI Agents](#mcp-server-for-ai-agents)
- [Building From Source](#building-from-source)
- [License](#license)

## Quick Start

You need an API key of the JetBrains Marketplace. You create it in your account on the
[JetBrains Marketplace](https://plugins.jetbrains.com/).

### With Docker

The Docker image is [jansorg/jetbrains-marketplace-stats](https://hub.docker.com/r/jansorg/jetbrains-marketplace-stats).

```bash
docker run \
  --env MARKETPLACE_API_KEY="your-api-key" \
  --publish 8080:8080 \
  --volume marketplace-stats-cache:/root/.cache/marketplace-stats \
  jansorg/jetbrains-marketplace-stats:latest
```

Open <http://localhost:8080> in your browser.

The image stores the [persistent cache](#persistent-cache) in `/root/.cache/marketplace-stats`.
The volume keeps the cache when you replace the container, e.g. with a new version.

### With docker-compose

The file `docker-compose.yml` is a template. It explains all settings in comments.

1. Copy `docker-compose.yml` to your server.
2. Replace `your-api-key` with your API key.
3. Optional: enable the [automatic refresh](#automatic-refresh) and the [custom events](#custom-events-in-the-charts).
4. Run `docker-compose up` to test the setup. Run `docker-compose up -d` to start it in the background.

### With Java

Download `marketplace-stats-all.jar` from the [releases on GitHub](https://github.com/jansorg/marketplace-stats-kotlin/releases).
This JAR file contains all dependencies.

```bash
java -jar marketplace-stats-all.jar --api-key="your-api-key"
```

Open <http://localhost:8080> in your browser.

## Requirements

- To run the JAR file: Java 25 or later.
- To build the application: JDK 27.
- The Docker image already contains Java.

## Configuration

There are three ways to configure the application. You can combine them.

1. **Command line options**, e.g. `--port=3030`.
2. **Environment variables**, e.g. `MARKETPLACE_SERVER_PORT=3030`. They are useful with Docker.
3. **A configuration file** in JSON format. Its path is the last argument, e.g.
   `java -jar marketplace-stats-all.jar /path/to/config.json`. A command line option or an environment variable
   overrides the value of the file.

### All Settings

| Option                                            | Environment variable              | Key of the config file | Default   | Description                                                                                                               |
|---------------------------------------------------|-----------------------------------|------------------------|-----------|---------------------------------------------------------------------------------------------------------------------------|
| `-k`, `--api-key`                                 | `MARKETPLACE_API_KEY`             | `apiKey`               | —         | The API key of the JetBrains Marketplace. Required.                                                                       |
| `-h`, `--host`                                    | `MARKETPLACE_SERVER_HOSTNAME`     | —                      | `0.0.0.0` | The IP address or hostname of the web server.                                                                             |
| `-p`, `--port`                                    | `MARKETPLACE_SERVER_PORT`         | —                      | `8080`    | The port of the web server.                                                                                               |
| `-c`, `--currency`                                | `MARKETPLACE_DISPLAY_CURRENCY`    | `displayedCurrency`    | `USD`     | The currency of the shown amounts, e.g. `EUR`.                                                                            |
| `--show-reseller-charges`                         | `MARKETPLACE_SHOW_RESELLER_CHARGES` | `showResellerCharges` | off       | Show the incorrect charges of sales through resellers.                                                                    |
| `--cache-dir`                                     | `MARKETPLACE_CACHE_DIR`           | `cacheDir`             | none      | The directory of the [persistent cache](#persistent-cache). Without it, the data is only kept in memory.                  |
| `--auto-refresh[=<minutes>]`                      | `MARKETPLACE_AUTO_REFRESH`        | —                      | off       | Load new data in the background, every 30 minutes or every `<minutes>`. See [Automatic Refresh](#automatic-refresh).      |
| `--events`                                        | `MARKETPLACE_EVENTS_FILE`         | `eventsFile`           | none      | A YAML file with your own events for the charts. See [Custom Events](#custom-events-in-the-charts).                       |
| `--all-plugins-summary`, `--no-all-plugins-summary` | `MARKETPLACE_ALL_PLUGINS_SUMMARY` | —                    | on        | Show the sales and downloads of all paid plugins above the list of plugins.                                               |
| `--mcp`, `--no-mcp`                               | `MARKETPLACE_MCP`                 | —                      | on        | Serve the [MCP server](#mcp-server-for-ai-agents) at `/mcp`.                                                              |
| `--mcp-allowed-hosts`                             | `MARKETPLACE_MCP_ALLOWED_HOSTS`   | —                      | none      | More hostnames for the MCP server, comma-separated. See [Security](#security).                                            |
| `-d`, `--debug`                                   | `MARKETPLACE_LOG_LEVEL`           | —                      | `none`    | The log level: `none`, `normal` or `verbose`.                                                                             |

Use `true` or `false` as the value of the environment variables of options that are on or off.
`java -jar marketplace-stats-all.jar --help` prints all options.

### Paths

The settings `--cache-dir` and `--events` contain a path:

- A leading `~` is your home directory. This also works in environment variables and in the configuration file.
- A relative path in the configuration file is relative to the directory of the configuration file.
- A relative path of an option or of an environment variable is relative to the working directory.

### Configuration File

`config-template.json` is a template:

```json
{
  "apiKey": "your-api-key",
  "displayedCurrency": "USD"
}
```

The file contains your API key. Don't share it, and don't add it to a Git repository.

## Persistent Cache

The application needs many requests to the Marketplace to load the data of a plugin.
By default, it only keeps the data in memory. After a restart, it loads all data again.

With `--cache-dir`, the application also stores the data in a directory:

```bash
java -jar marketplace-stats-all.jar \
  --api-key="your-api-key" \
  --cache-dir="~/.cache/marketplace-stats"
```

The Docker image uses a cache in `/root/.cache/marketplace-stats` by default. An empty `MARKETPLACE_CACHE_DIR` disables it.

### When Is the Data Loaded Again?

| Data                                  | Loaded again                                                                                     |
|---------------------------------------|--------------------------------------------------------------------------------------------------|
| Sales and trials of the last 45 days  | After each start (in the background), and when a report needs data older than 30 minutes       |
| All sales and trials                  | Once a week                                                                                      |
| Downloads, ratings, releases          | Once a day                                                                                       |
| Products and releases of JetBrains    | Once a day                                                                                       |
| Prices                                | Once a week                                                                                      |
| Exchange rates                        | After six hours                                                                                  |

After a restart, the reports show the stored data immediately. If the stored data is older than a day, e.g. after a
long downtime, the application loads the recent data before it shows the report.

The button "Drop caches" in the footer of a report deletes the cached data of the plugin. The application then loads
all data again.

### Good to Know

- Each API key has its own directory. The API key itself is not stored.
- The products of JetBrains are stored once for all API keys.
- The directory contains sales and customer data. Protect it like other private data.
- You can delete the directory at any time. The application then loads all data again.
- The application ignores and replaces damaged files and files of other versions.

## Automatic Refresh

The Marketplace API is slow. Without the automatic refresh, a report loads new data when the cached data is older than
30 minutes. The page waits for this.

With the automatic refresh, the application loads new data in the background. The pages always use the cached data and
don't wait.

```bash
java -jar marketplace-stats-all.jar \
  --api-key="your-api-key" \
  --cache-dir="~/.cache/marketplace-stats" \
  --auto-refresh
```

- `--auto-refresh` loads new data every 30 minutes. `--auto-refresh=15` loads it every 15 minutes.
- With Docker, use `MARKETPLACE_AUTO_REFRESH=30`. The value is the interval in minutes. `0` disables it.
- The automatic refresh is off by default.

How it works:

- The application only refreshes plugins that you opened: a report page of the plugin, a request of the MCP server for
  the plugin, or the summary of all plugins on the start page. It doesn't refresh other plugins.
- 24 hours after the last request for a plugin, the refresh of this plugin stops. The next request loads the data again
  and starts the refresh again.
- Each refresh loads the recent data, e.g. the sales and trials of the last 45 days. Data that changes less often,
  e.g. downloads or prices, is only loaded when it would be outdated before the next refresh.
- If a refresh fails, the application keeps the previous data and uses it.

## Custom Events in the Charts

The line charts can show vertical lines for the releases of your plugin, its price changes and the releases of
IntelliJ IDEA. Other events are not part of the data of the Marketplace, e.g. a blog post or a change of the trial
period. You can add such events with a YAML file.

### File Format

```yaml
events:
  - id: price-announcement           # optional: the ID of the event, the default is the date and the label
    plugin: 12345                    # required: the ID of the plugin
    date: 2026-10-16                 # required: the date in the format YYYY-MM-DD
    label: "Announced price change"  # required: the text above the line

  - trial-period:                    # a key without a value is also the ID
    plugin: 12345
    date: 2026-03-01
    label: "Trial period of 30 days"
```

- You find the ID of a plugin in the address of its report, e.g. `?pluginId=12345`.
- The IDs of the events must be unique.
- `events-template.yml` is a template. Copy it, e.g. to `events.yml`, and change the events.

### Use the File

Set the path of the file with `--events`, with `MARKETPLACE_EVENTS_FILE` or with `eventsFile` in the configuration file:

```bash
java -jar marketplace-stats-all.jar \
  --api-key="your-api-key" \
  --events="~/marketplace-stats/events.yaml"
```

In the chart dialog, the menu "Events" lists the events of the plugin. Select an event to show it as a vertical line.
Your browser stores the selection. The summary of all plugins on the start page shows the events of all plugins.

The application reads the file again when you change it. You don't need to restart it.

- If the file is invalid when the application starts, the application doesn't start. The error message names the
  event and the problem.
- If you change the file and it becomes invalid, the application writes the error to the log and keeps the previous
  events.

### With Docker

Mount the directory of the file into the container, read-only. Then set the path of the file in the container:

```yaml
    environment:
      - MARKETPLACE_EVENTS_FILE=/config/events.yaml
    volumes:
      - ./config:/config:ro
```

Mount the directory, not the file. Many editors replace a file when they save it. A mounted file would then keep its
old content in the container.

## MCP Server for AI Agents

The web server also provides an [MCP](https://modelcontextprotocol.io/) server at `/mcp`, e.g. `http://localhost:8080/mcp`.
AI agents can use it to analyze the sales and the trends of your plugins.
It uses the Streamable HTTP transport and has no sessions.

For example, to add it to Claude Code:

```bash
claude mcp add --transport http marketplace-stats http://localhost:8080/mcp
```

### MCP Tools

#### Account and plugins

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `list_plugins`                     | The account of the API key, its plugins with their IDs, and the monetization of paid plugins            |

#### Tables of the report pages

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `get_yearly_summary`               | Sales, fees, ARR, downloads and trials per year                                                         |
| `get_monthly_overview`             | Sales, licenses, MRR, ARR, churn, downloads and trials per month                                        |
| `get_daily_summary`                | Sales, downloads and trials per day                                                                     |
| `get_sales_and_trials_breakdown`   | Sales by license period and customer type, trials by country                                            |
| `get_top_countries`                | Sales, trials and trial conversion by country, sorted by sales or trials                                |
| `get_mrr_movements`                | Changes of the MRR by new, expansion, contraction, churned and reactivated customers (experimental)     |
| `get_revenue_retention`            | Gross and net revenue retention per month (experimental)                                                |
| `get_customer_cohorts`             | Customer and revenue retention of the customers by the year of their first payment (experimental)       |
| `get_lifetime_value`               | Realized lifetime value per cohort year (experimental)                                                  |
| `get_annual_renewal_rates`         | Renewal rates of annual subscriptions per year (experimental)                                           |
| `get_monthly_renewal_rates`        | Renewal rates of monthly subscriptions per month (experimental)                                         |
| `get_average_revenue_per_customer` | Paying customers and average recurring revenue per customer (experimental)                              |

#### Sales, customers, licenses and trials

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `list_sales`                       | Sales with customer, amounts and line items, with filters, totals and grouping                          |
| `list_customers`                   | Customers with status (active or inactive), active, churned and transferred licenses and total sales    |
| `list_licenses`                    | Licenses with status, validity and renewals, e.g. licenses that expire in a month                       |
| `list_customer_licenses`           | Licenses of a customer with their status and purchases                                                  |
| `list_trials`                      | Single trials with customer and conversion, and a summary per month                                     |
| `get_license_cohorts`              | Renewals of the subscription licenses by the month of their first paid subscription                     |
| `list_license_transfers`           | Licenses bought by another customer than before, e.g. moved from an individual to an organization       |

#### Prices and exchange rates

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `get_pricing`                      | Current prices by country, customer type and license period, countries with the same prices are grouped |
| `get_price_changes`                | Changes of the annual base price, derived from the sales                                                |
| `list_offers`                      | Special offers with discount or free months and the number of their coupons, without the codes          |
| `get_exchange_rates`               | ECB exchange rates of a date, and the currencies supported by the `currency` argument                   |

#### Downloads, reviews and visitors

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `get_downloads`                    | Downloads per month or day by IDE product, plugin version, or both                                      |
| `list_reviews`                     | Reviews with rating and date, average rating per month, current rating                                  |
| `get_page_views`                   | Visitors of the plugin page by day, country and source (sampled Google Analytics data)                  |

#### Releases of the plugin and of JetBrains products

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `list_plugin_releases`             | Releases of a plugin and the first release of each major version                                        |
| `list_jetbrains_products`          | JetBrains products with their metadata                                                                  |
| `list_jetbrains_product_releases`  | Versions of a JetBrains product with date and build, or its major releases                              |
| `get_jetbrains_product_release`    | Metadata of a release of a JetBrains product, optionally with what's new, downloads and patches         |

#### Cache

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `refresh_data`                     | Deletes the cached data of a plugin or of all plugins, like the button "Drop caches" of the reports     |

### Arguments and Results

- **Tables:** the table tools return the tables of the overview page of a plugin as JSON. They take the ID of the plugin
  and an optional date range `startDate` and `endDate` (`YYYY-MM-DD`, inclusive). Without a date range, they return all
  data.
- **Tables per day, month or year:** these are calculated with all data, and only the rows of the date range are
  returned. This keeps values like the recurring revenue and the churn correct. The other tables only use the sales,
  licenses and trials of the date range.
- **Columns and values:** each column has a unique key, a title and a unit, e.g. the currency or `%`. The rows contain
  plain numbers. Values of the current period are marked as incomplete. Churn rates include the numbers of churned and
  previously active licenses.
- **Lists:** the other tools return lists of sales, customers, licenses, trials, reviews, prices and releases. Lists
  accept `limit` and `offset` and return the total count.
- **Amounts:** amounts are in the displayed currency. They are also in USD and in the currency of the customer, if these
  are different. Sales include the fee of the Marketplace. The tools with amounts accept an optional `currency`, e.g.
  `EUR`. The amounts are then converted with the ECB exchange rate of the day of each sale. `get_exchange_rates` lists
  the supported currencies.
- **Enum values** are lower camel case, e.g. `individual` or `annual`.
- **Conventions:** the instructions of the MCP server describe the conventions once, not in each tool. This saves
  tokens. Unknown arguments are rejected.

Limits of the data: the Marketplace API has no downloads by IDE version and no installs. The page views of
`get_page_views` are sampled data of Google Analytics. The Marketplace warns that page visits and referrals may be
inaccurate.

### Security

The MCP server has no authentication, like the web interface. To protect against DNS rebinding attacks, it only accepts
requests for the hostnames `localhost`, `127.0.0.1`, `[::1]` and the hostname of `--host`.

- If you use another hostname, e.g. with Docker on a server, add it with `--mcp-allowed-hosts`.
- `--no-mcp` disables the MCP server.

## Building From Source

You need JDK 27. Gradle downloads it, if it isn't installed.

```bash
./gradlew build      # compiles the code and runs the tests
./gradlew shadowJar  # creates build/libs/marketplace-stats-all.jar
```

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE as
published by the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU AFFERO GENERAL PUBLIC LICENSE for more details.

You should have received a copy of the GNU AFFERO GENERAL PUBLIC LICENSE along with this program. If not,
see <https://www.gnu.org/licenses/>.
