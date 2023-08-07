# JetBrains Marketplace Statistics

This software provides statistics for paid plugins on the [JetBrains Marketplace](https://plugins.jetbrains.com/).
It's a redevelopment of [marketplace-stats](https://github.com/jansorg/marketplace-stats) as a web-based application using Kotlin (instead of Golang).

## Usage

1. Prepare your configuration file. You can find a template at `config-template.json`. The file contains the plugin ID and the API key to access the JetBrains Marketplace.
   ```json
   {
      "pluginId": "your plugin ID as a number, e.g. 123456", 
      "apiKey": "the API key to access the JetBrains marketplace"
   }
   ```
2. Execute and build the application
    ```bash
   # Build
    ./gradlew clean build -x test
   # Launch
    java -jar ./build/libs/marketplace-client-all.jar /path/to/config.json
    ```
3. Open http://localhost:8080 in your browser to use the application.

## Development
TODO 

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE as published by the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU AFFERO GENERAL PUBLIC LICENSE for more details.

You should have received a copy of the GNU AFFERO GENERAL PUBLIC LICENSE along with this program. If not,
see <https://www.gnu.org/licenses/>.