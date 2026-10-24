# JetBrains Marketplace Statistics

This software provides statistics for paid plugins on the [JetBrains Marketplace](https://plugins.jetbrains.com/).
It's a redevelopment of [marketplace-stats](https://github.com/jansorg/marketplace-stats) as a web-based application using Kotlin instead of
Golang.

## How to Get It

Releases are available [on GitHub](https://github.com/jansorg/marketplace-stats-kotlin/releases).
Release asset `marketplace-stats-all.jar` is a JAR file with all dependencies in it.
It can be launched with `java -jar`:

```bash
java -jar /path/to/marketplace-stats-all.jar \
  --api-key="Your JetBrains API key" ]
```

## Requirements

Building this software requires JDK 27. The application runs on Java 25 or later.

## Building

If you don't want to use the release assets, you can build it yourself:

```bash
# Build
./gradlew clean build
```

## Usage

The application launches an integrated HTTP server to provide the reports.
The default is port `8080`: [http://localhost:8080](http://localhost:8080).

An API key for the JetBrains Marketplace is required.

Command line option `--help` prints usage instructions:

```text
Usage: marketplace-stats [<options>] [<config.json file path>]

  Marketplace Stats provides reports for plugins hosted on the JetBrains Marketplace.

Options:
  --version                          Show the version and exit
  -k, --api-key=<text>               API key for the JetBrains Marketplace. The key is used to find available plugins and to load the data needed to
                                     generate a plugin report.
  -h, --host=<text>                  IP address or hostname the integrated webserver is bound to. (default: 0.0.0.0)
  -p, --port=<int>                   Port used by the integrated webserver. (default: 8080)
  -c, --currency=<text>              Currency for the displayed monetary amounts.
  --cache-dir=<text>                 Directory of the persistent cache. Sales, trials, prices and exchange rates are stored there to speed up
                                     restarts. The data is kept in memory only if no directory is set or if it's empty. A leading ~ is the
                                     home directory.
  --show-reseller-charges            Show incorrect charges for sales through resellers.
  --mcp / --no-mcp                   Serve the MCP server for AI agents at /mcp of the integrated webserver. (default: enabled)
  --mcp-allowed-hosts=<text>         Comma-separated hostnames, which are accepted in the Host and Origin headers of requests to the MCP server, in
                                     addition to localhost, 127.0.0.1, [::1] and the hostname of --host. Ports are ignored. Protects against DNS
                                     rebinding attacks.
                                     (default: none)
  -d, --debug=(none|normal|verbose)  The log level used for the server and the API requests to the marketplace (default: None)
  --help                             Show this message and exit

Arguments:
  <config.json file path>  Path to the application configuration JSON file. It's used as fallback for the other command line options. A template is
                           available at https://github.com/jansorg/marketplace-stats-kotlin/blob/main/config-template.json.
```

### Launch With the Command Line

```bash
java -jar ./build/libs/marketplace-stats-all.jar \
  --api-key="Your JetBrains API key"
```

Use `--port` to use your own port

```bash
java -jar ./build/libs/marketplace-stats-all.jar \
  --api-key="Your JetBrains API key" \
  --port=3030
```

### Launch With Environment Variables

Instead of options, you can use the following environment variables:

```text
--api-key:           MARKETPLACE_API_KEY
--port:              MARKETPLACE_SERVER_PORT
--host:              MARKETPLACE_SERVER_HOSTNAME
--display-currency:  MARKETPLACE_DISPLAY_CURRENCY
--debug:             MARKETPLACE_LOG_LEVEL
--cache-dir:         MARKETPLACE_CACHE_DIR
--mcp, --no-mcp:     MARKETPLACE_MCP (true or false)
--mcp-allowed-hosts: MARKETPLACE_MCP_ALLOWED_HOSTS
```

For example:

```bash
export MARKETPLACE_API_KEY="Your JetBrains API key"
export MARKETPLACE_SERVER_PORT=3030
java -jar ./build/libs/marketplace-stats-all.jar
```

### Launch With a Configuration File

1. Prepare your configuration file. You can find a template at `config-template.json`. The file contains the plugin ID and the API key to
   access the JetBrains Marketplace.
   ```json
   {
      "apiKey": "the API key to access the JetBrains marketplace"
   }
   ```
2. Launch the application:
    ```bash
    java -jar ./build/libs/marketplace-stats-all.jar /path/to/config.json
    ```
3. Open http://localhost:8080 in your browser to use the application.

The configuration file also supports the keys `displayedCurrency`, `showResellerCharges` and `cacheDir`.

### Persistent Cache

Loading the sales, trials, prices and downloads of a plugin takes many requests to the JetBrains Marketplace.
By default, the data is only cached in memory and loaded again after a restart.
With `--cache-dir` (or `MARKETPLACE_CACHE_DIR`), the data is also stored in the given directory:

```bash
java -jar ./build/libs/marketplace-stats-all.jar \
  --api-key="Your JetBrains API key" \
  --cache-dir="$HOME/.cache/marketplace-stats"
```

A leading `~` is the home directory, also in `MARKETPLACE_CACHE_DIR` and in the configuration file.
A relative `cacheDir` of the configuration file is relative to the directory of the file,
a relative `--cache-dir` or `MARKETPLACE_CACHE_DIR` to the working directory.

After a restart, the stored data is shown immediately. The sales and trials of the last 45 days and newer ones are
loaded again in the background right after the start, for all plugins with stored data and also if the stored data is
recent, to catch changes like refunds. If the stored data is
older than a day, for example after a long downtime, they are loaded before the report is shown.
While the server is running, they are loaded again when a report is shown and the data is older than 30 minutes.
The complete history is loaded again once a week.
Prices are loaded again after a week, exchange rates after six hours, downloads and the products and releases of JetBrains after a day.
The "Refresh" button of a report (`POST /refresh?reload=true`) ignores the stored data and loads everything again.

The data of each API key is stored in a separate directory, the key itself isn't stored. The products of JetBrains are shared by all keys.
The directory contains sales and customer data, protect it accordingly. It's safe to delete it at any time.
Files of an older or newer version of the application, and damaged files, are ignored and replaced.

## MCP Server for AI Agents

The web server also provides an [MCP](https://modelcontextprotocol.io/) server at `/mcp`,
e.g. `http://localhost:8080/mcp`. It uses the Streamable HTTP transport.
AI agents can use it to analyze the sales and trends of your plugins.

For example, to add it to Claude Code:

```bash
claude mcp add --transport http marketplace-stats http://localhost:8080/mcp
```

### MCP Tools

#### Account and plugins

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `list_plugins`                     | The account of the API key and its plugins with their IDs                                               |

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
| `get_monthly_renewal_rates`        | Renewal rates of monthly subscriptions per year (experimental)                                          |
| `get_average_revenue_per_customer` | Paying customers and average recurring revenue per customer (experimental)                              |

#### Sales, customers, licenses and trials

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `list_sales`                       | Sales with customer, amounts and line items, with filters, totals and grouping                          |
| `list_customers`                   | Customers with status (active or churned), churn date, licenses and total sales                         |
| `list_licenses`                    | Licenses with status, validity and renewals, e.g. licenses expiring in a month                          |
| `list_customer_licenses`           | Licenses of a customer with their status and purchases                                                  |
| `list_trials`                      | Single trials with customer and conversion, and a summary per month                                     |
| `get_license_cohorts`              | Renewals of the subscription licenses by the month of their first paid subscription                     |
| `list_license_transfers`           | Licenses bought by another customer than before, e.g. moved from an individual to an organization       |

#### Prices and exchange rates

| Tool                               | Data                                                                                                    |
|------------------------------------|---------------------------------------------------------------------------------------------------------|
| `get_pricing`                      | Current prices by country, customer type and license period, countries with the same prices are grouped |
| `get_price_changes`                | Changes of the annual base price, derived from the sales                                                |
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
| `refresh_data`                     | Drops the cached data of a plugin or of all plugins, like the reload of the web interface               |

### Arguments and Results

The table tools of a plugin provide the tables of the plugin's overview page as JSON. They take the plugin ID and an optional date range
`startDate` and `endDate` (`YYYY-MM-DD`, inclusive). Without a date range, all available data is returned.
Tables with a row per day, month or year are calculated with all data and only the rows of the date range are returned,
so that values like the recurring revenue and churn stay correct. The other tables only use the sales, licenses and trials of the date range.

Table results have columns with a unique key, a title and a unit, e.g. the currency or `%`, and rows with plain numbers.
Values of the current period are marked as incomplete, churn rates include the numbers of churned and previously active licenses.

The other tools return lists of sales, customers, licenses, trials, reviews, prices and releases. Lists accept `limit` and `offset`
and return the total count. Amounts are returned in the displayed currency, and also in USD and in the currency paid by the customer,
if they differ. Sales and purchases include the fee of the Marketplace. Enum values are lower camel case, e.g. `individual` or `annual`. The tools with amounts accept an optional `currency`, e.g. `EUR`, to convert the amounts with the ECB exchange rate
of the day of each sale, see `get_exchange_rates` for the supported currencies.

The conventions of the arguments and results are described once in the instructions of the MCP server, not in each tool,
to save tokens. Unknown arguments are rejected.

The Marketplace API doesn't provide downloads by IDE version and installs. The page views of `get_page_views` are sampled data of
Google Analytics, the Marketplace warns that page visits and referrals may be inaccurate.

### Security

The MCP server has no authentication, like the web interface. To protect against DNS rebinding attacks,
it only accepts requests for the hostnames `localhost`, `127.0.0.1`, `[::1]` and the hostname of `--host`.
If you access the server with another hostname, e.g. in a Docker deployment, add it with `--mcp-allowed-hosts`.
Use `--no-mcp` to disable the MCP server.

## Deployment

### Deploy with Docker

[jansorg/jetbrains-marketplace-stats](https://hub.docker.com/r/jansorg/jetbrains-marketplace-stats) is a Docker container.

```bash
docker run \
  --env MARKETPLACE_API_KEY="your-api-key" \
  --publish 8080:8080 \
  --volume marketplace-stats-cache:/root/.cache/marketplace-stats \
  jansorg/jetbrains-marketplace-stats:latest
```

The image stores the [persistent cache](#persistent-cache) in `/root/.cache/marketplace-stats`.
Mount a volume there to keep the cache when the container is replaced, e.g. by a new version.
An empty `MARKETPLACE_CACHE_DIR` disables the cache.

### Deploy with docker-compose

Please refer to `docker-compose.yml` for a template setup.

1. Copy `docker-compose.yml` to your server.
2. Add your API key in the file.
3. Run `docker-compose up` to test it and `docker-compose up -d` to launch it in the background.

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE as
published by the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU AFFERO GENERAL PUBLIC LICENSE for more details.

You should have received a copy of the GNU AFFERO GENERAL PUBLIC LICENSE along with this program. If not,
see <https://www.gnu.org/licenses/>.