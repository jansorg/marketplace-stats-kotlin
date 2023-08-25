# JetBrains Marketplace Statistics

This software provides statistics for paid plugins on the [JetBrains Marketplace](https://plugins.jetbrains.com/).
It's a redevelopment of [marketplace-stats](https://github.com/jansorg/marketplace-stats) as a web-based application using Kotlin (instead of Golang).

## Usage

The application provides an HTTP server on port 8080: [http://localhost:8080](http://localhost:8080).

The port is not yet configurable, it's made available on all network interfaces (not just 127.0.0.1).

### Launch With a Configuration File

1. Prepare your configuration file. You can find a template at `config-template.json`. The file contains the plugin ID and the API key to access the JetBrains Marketplace.
   ```json
   {
      "apiKey": "the API key to access the JetBrains marketplace"
   }
   ```
2. Build and launch the application:
    ```bash
   # Build
    ./gradlew clean build -x test
   # Launch with configuration file
    java -jar ./build/libs/marketplace-stats-all.jar /path/to/config.json
    ```
3. Open http://localhost:8080 in your browser to use the application.

### Launch With an Environment Variable

If no configuration file is provided on the command line, the API key is retrieved from the environment variable `MARKETPLACE_API_KEY`.

```bash
   # Build
    ./gradlew clean build -x test
   # Launch with environment variable setup
    MARKETPLACE_API_KEY="your-api-key" java -jar ./build/libs/marketplace-stats-all.jar
```

## Deployment

### Execute with Docker

[jansorg/jetbrains-marketplace-stats](https://hub.docker.com/r/jansorg/jetbrains-marketplace-stats) is a Docker container.

```bash
docker run \
  jansorg/jetbrains-marketplace-stats:latest \
  --env MARKETPLACE_API_KEY="your-api-key" 
```

### Execute with docker-compose

Please refer to `docker-compose.yml` for a template setup.

1. Copy `docker-compose.yml` to your server.
2. Add your API key in the file.
3. Run `docker-compose up` to test it and `docker-compose up -d` to launch it in the background. 

## Development
TODO 

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE as published by the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU AFFERO GENERAL PUBLIC LICENSE for more details.

You should have received a copy of the GNU AFFERO GENERAL PUBLIC LICENSE along with this program. If not,
see <https://www.gnu.org/licenses/>.