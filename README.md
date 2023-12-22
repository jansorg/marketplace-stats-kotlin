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
--api-key: MARKETPLACE_API_KEY
--port:    MARKETPLACE_SERVER_PORT
--host:    MARKETPLACE_SERVER_HOSTNAME
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

## Deployment

### Deploy with Docker

[jansorg/jetbrains-marketplace-stats](https://hub.docker.com/r/jansorg/jetbrains-marketplace-stats) is a Docker container.

```bash
docker run \
  jansorg/jetbrains-marketplace-stats:latest \
  --env MARKETPLACE_API_KEY="your-api-key" 
```

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