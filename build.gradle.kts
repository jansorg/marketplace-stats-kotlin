/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import gg.jte.ContentType
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// https://mvnrepository.com/artifact/io.ktor/ktor-client-core-jvm
val ktorVersion = project.property("ktorVersion") as String

plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"

    // https://plugins.gradle.org/plugin/com.gradleup.shadow
    id("com.gradleup.shadow") version "9.6.1"
    // https://plugins.gradle.org/plugin/gg.jte.gradle
    id("gg.jte.gradle") version "3.2.4"
    // https://github.com/gmazzo/gradle-buildconfig-plugin
    id("com.github.gmazzo.buildconfig") version "6.1.2"
}

allprojects {
    repositories {
        mavenCentral()
    }

    apply {
        plugin("kotlin")
        plugin("org.jetbrains.kotlin.plugin.serialization")
    }

    kotlin {
        // Kotlin 2.3 doesn't support JVM 27 bytecode yet, build with JDK 27 but target the latest LTS
        jvmToolchain(27)
        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
            freeCompilerArgs.add("-Xdebug")
        }
    }

    java {
        targetCompatibility = JavaVersion.VERSION_25
    }

    tasks {
        test {
            useJUnitPlatform()
        }
    }

    // The public API of the modules uses the types of these libraries, so they are api dependencies of the modules,
    // e.g. for users of the modules as libraries. The web application only uses them internally.
    val exposedDependency = if (this == rootProject) "implementation" else "api"

    dependencies {
        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-datetime-jvm
        // the 0.6.x-compat variant is required by Ktor and would win the conflict resolution anyway
        exposedDependency("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm
        exposedDependency("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
        exposedDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

        // https://mvnrepository.com/artifact/it.unimi.dsi/fastutil-core
        exposedDependency("it.unimi.dsi:fastutil-core:8.5.19")

        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-core
        exposedDependency("org.javamoney.moneta:moneta-core:1.4.5")

        // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
        implementation("com.github.ben-manes.caffeine:caffeine:3.3.1")
        // https://mvnrepository.com/artifact/dev.hsbrysk/caffeine-coroutines
        implementation("dev.hsbrysk:caffeine-coroutines:2.0.4")

        // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
        testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        // JUnit needs it to run suspending test functions
        testRuntimeOnly(kotlin("reflect"))
    }
}

project("marketplace-client") {
    description = "Client for the API of the JetBrains Marketplace and the services of JetBrains, with caching and currency conversion"

    dependencies {
        api("io.ktor:ktor-client-core:$ktorVersion")
        api("io.ktor:ktor-client-java:$ktorVersion")
        api("io.ktor:ktor-client-logging:$ktorVersion")
        api("io.ktor:ktor-client-resources:$ktorVersion")
        api("io.ktor:ktor-client-content-negotiation:$ktorVersion")
        api("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-protobuf-jvm
        // format of the persistent cache, must have the same version as kotlinx-serialization-json
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")

        testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    }
}

project(":marketplace-data") {
    description = "Reports and analysis of the sales, licenses, customers and trials of paid plugins of the JetBrains Marketplace"

    dependencies {
        // api: the public API of marketplace-data uses the types of marketplace-client
        api(project(":marketplace-client"))
    }
}

// The modules are published as libraries to the self-hosted Reposilite at https://maven.j-a.dev, see "Libraries" in README.md.
// The web application of the root project isn't published.
configure(listOf(project(":marketplace-client"), project(":marketplace-data"))) {
    group = "dev.j-a.jetbrains-marketplace"
    version = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION.txt")).asText.get().trim()

    apply {
        plugin("maven-publish")
    }

    java {
        withSourcesJar()
    }

    // Dokka isn't set up, the JAR only contains the manifest
    tasks.register<Jar>("javadocEmpty") {
        description = "Generates empty JavaDoc"
        archiveClassifier = "javadoc"
    }

    configure<PublishingExtension> {
        repositories {
            mavenLocal()

            // the self-hosted Reposilite, the credentials are the name and the secret of an access token,
            // the Gradle properties jaDevUsername and jaDevPassword, e.g. ORG_GRADLE_PROJECT_jaDevUsername
            maven {
                name = "jaDev"
                url = uri(if (version.toString().endsWith("-SNAPSHOT")) "https://maven.j-a.dev/snapshots" else "https://maven.j-a.dev/releases")
                credentials(PasswordCredentials::class)
            }
        }

        publications {
            create<MavenPublication>("library") {
                from(components["java"])

                artifact(tasks.named("javadocEmpty")) {
                    classifier = "javadoc"
                }

                pom {
                    name = project.name
                    description = project.description
                    url = "https://github.com/jansorg/marketplace-stats-kotlin"
                    inceptionYear = "2023"

                    licenses {
                        license {
                            name = "GNU Affero General Public License v3.0 or later"
                            url = "https://www.gnu.org/licenses/agpl-3.0.txt"
                            distribution = "repo"
                        }
                    }

                    scm {
                        url = "https://github.com/jansorg/marketplace-stats-kotlin"
                    }

                    developers {
                        developer {
                            id = "jansorg"
                            name = "Joachim Ansorg"
                            email = "mail@ja-dev.eu"
                            url = "https://github.com/jansorg"
                        }
                    }
                }
            }
        }
    }
}

project(":") {
    repositories {
        // JavaScript libraries are downloaded from the npm registry and bundled with the server
        exclusiveContent {
            forRepository {
                ivy {
                    name = "npm"
                    url = uri("https://registry.npmjs.org")
                    patternLayout {
                        artifact("[module]/-/[module]-[revision].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("npm")
            }
        }
        // the packages of the scope @fontsource, e.g. @fontsource/lato
        exclusiveContent {
            forRepository {
                ivy {
                    name = "npm-fontsource"
                    url = uri("https://registry.npmjs.org/@fontsource")
                    patternLayout {
                        artifact("[module]/-/[module]-[revision].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("npm-fontsource")
            }
        }
    }

    val javascriptLibraries by configurations.creating {
        isCanBeConsumed = false
        isTransitive = false
    }

    dependencies {
        // https://www.npmjs.com/package/uplot, used for the charts
        javascriptLibraries("npm:uplot:1.6.32@tgz")
        // https://www.npmjs.com/package/sortable-tablesort, used for the sortable tables
        javascriptLibraries("npm:sortable-tablesort:4.1.7@tgz")
        // https://www.npmjs.com/package/@fontsource/lato, the font of the pages, served instead of Google Fonts
        javascriptLibraries("npm-fontsource:lato:5.3.0@tgz")
    }

    // Extracts the files of the JavaScript libraries, which are served from the "js" directory of the resources.
    // ArchiveOperations instead of Project.tarTree is compatible with the configuration cache.
    val archives = objects.newInstance<InjectedArchiveOperations>().archiveOperations
    // the files of a library, the packages of npm contain their files in the directory "package"
    fun library(name: String) = javascriptLibraries.elements.map { files ->
        files.filter { it.asFile.name.startsWith("$name-") }.map { archives.tarTree(archives.gzip(it.asFile)) }
    }

    // the served files of each library: the paths in the package and in the resources
    val javascriptLibraryFiles = mapOf(
        "uplot" to mapOf(
            "package/dist/uPlot.iife.min.js" to "js/uPlot.iife.min.js",
            "package/dist/uPlot.min.css" to "js/uPlot.min.css",
            "package/LICENSE" to "js/uPlot.LICENSE.txt",
        ),
        // the base styles only contain the arrows of the sorting, sortable.min.css also styles the table like the
        // example of the package, e.g. with gray headers, which doesn't fit the tables of the pages
        "sortable-tablesort" to mapOf(
            "package/dist/sortable.min.js" to "js/sortable.min.js",
            "package/dist/sortable-base.min.css" to "js/sortable-base.min.css",
            "package/LICENSE" to "js/sortable.LICENSE.txt",
        ),
        // the subsets latin and latin-ext of the styles of the pages, see fonts.kte
        "lato" to mapOf(
            "package/files/lato-latin-400-normal.woff2" to "fonts/lato-latin-400-normal.woff2",
            "package/files/lato-latin-ext-400-normal.woff2" to "fonts/lato-latin-ext-400-normal.woff2",
            "package/files/lato-latin-700-normal.woff2" to "fonts/lato-latin-700-normal.woff2",
            "package/files/lato-latin-ext-700-normal.woff2" to "fonts/lato-latin-ext-700-normal.woff2",
            "package/files/lato-latin-400-italic.woff2" to "fonts/lato-latin-400-italic.woff2",
            "package/files/lato-latin-ext-400-italic.woff2" to "fonts/lato-latin-ext-400-italic.woff2",
            "package/LICENSE" to "fonts/Lato.LICENSE.txt",
        ),
    )

    val bundleJavascriptLibraries by tasks.registering(Sync::class) {
        for ((libraryName, files) in javascriptLibraryFiles) {
            from(library(libraryName)) {
                include(files.keys)
                eachFile {
                    relativePath = RelativePath.parse(true, files.getValue(path))
                }
                includeEmptyDirs = false
            }
        }
        // Gradle doesn't notice changes of the patterns and the names of the files of sources from providers.
        // Without this input, the task and the JARs would keep the files of the previous configuration.
        inputs.property("libraryFiles", javascriptLibraryFiles)
        into(layout.buildDirectory.dir("generated/javascript"))
    }

    // The Closure Compiler bundles the ES modules of the charts into one minified file, it runs on the JVM,
    // the build doesn't need Node.js
    val closureCompiler by configurations.creating {
        isCanBeConsumed = false
    }

    dependencies {
        // https://github.com/google/closure-compiler
        closureCompiler("com.google.javascript:closure-compiler:v20261021")
    }

    // the modules of the charts, main.js and its imports
    val bundleChartsJavascript by tasks.registering(ClosureBundle::class) {
        description = "Bundles and minifies the JavaScript modules of the charts"
        bundleName = "charts"
        sourceDirectory = layout.projectDirectory.dir("src/main/resources/js/charts")
        sources.from(sourceDirectory.asFileTree.matching { include("**/*.js") })
        entryPoint = "main.js"
        compilerClasspath.from(closureCompiler)
        javaLauncher = javaToolchains.launcherFor(java.toolchain)
        outputDirectory = layout.buildDirectory.dir("generated/bundleChartsJavascript")
    }

    // the script of the tables with collapsed rows
    val minifyCollapsibleTablesJavascript by tasks.registering(ClosureBundle::class) {
        description = "Minifies the JavaScript of the collapsible tables"
        bundleName = "collapsibleTables"
        sourceDirectory = layout.projectDirectory.dir("src/main/resources/js")
        sources.from(sourceDirectory.file("collapsibleTables.js"))
        entryPoint = "collapsibleTables.js"
        compilerClasspath.from(closureCompiler)
        javaLauncher = javaToolchains.launcherFor(java.toolchain)
        outputDirectory = layout.buildDirectory.dir("generated/minifyCollapsibleTablesJavascript")
    }

    sourceSets.main {
        resources.srcDir(bundleJavascriptLibraries)
        resources.srcDir(bundleChartsJavascript)
        resources.srcDir(minifyCollapsibleTablesJavascript)
    }

    // the stylesheets are served minified, e.g. styles/main.min.css
    val minifyStylesheets by tasks.registering(MinifyCss::class) {
        description = "Minifies the stylesheets"
        source.from(fileTree("src/main/resources/styles") { include("*.css") })
        // the styles of the sortable tables and of uPlot, after the own styles like the links of the pages before
        appendedToMain.from(bundleJavascriptLibraries.map { it.destinationDir.resolve("js/sortable-base.min.css") })
        appendedToMain.from(bundleJavascriptLibraries.map { it.destinationDir.resolve("js/uPlot.min.css") })
        outputDirectory.set(layout.buildDirectory.dir("generated/stylesheets/styles"))
    }

    sourceSets.main {
        resources.srcDir(minifyStylesheets.map { it.outputDirectory.get().asFile.parentFile })
    }

    // the pages load the minified scripts and stylesheets instead of their sources
    tasks.processResources {
        exclude("js/charts/**", "js/collapsibleTables.js")
        exclude { it.relativePath.pathString.startsWith("styles/") && !it.name.endsWith(".min.css") }
    }

    // Tests of the JavaScript modules of the charts, with the test runner of Node.js. Skipped if Node.js isn't installed.
    val isNodeAvailable = try {
        providers.exec {
            commandLine("node", "--version")
            isIgnoreExitValue = true
        }.result.get().exitValue == 0
    } catch (e: Exception) {
        false
    }

    val testJavascript by tasks.registering(Exec::class) {
        description = "Runs the tests of the JavaScript modules with Node.js"
        group = "verification"
        onlyIf("Node.js is available") { isNodeAvailable }
        inputs.dir("src/main/resources/js/charts")
        inputs.dir("src/test/js")
        workingDir = projectDir
        // the test files are passed explicitly, older versions of Node.js don't accept a directory
        val testFiles = fileTree("src/test/js") { include("**/*.test.mjs") }.files.map { it.relativeTo(projectDir).path }.sorted()
        commandLine(listOf("node", "--test") + testFiles)
    }

    tasks.named("check") {
        dependsOn(testJavascript)
    }

    dependencies {
        // api instead of implementation: Shadow's minimize keeps all classes of api dependencies,
        // some classes are only used by the precompiled JTE templates, e.g. Formatters and ChartValues
        api(project(":marketplace-client"))
        api(project(":marketplace-data"))

        // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
        // only the application, the libraries log with SLF4J and leave the choice of the backend to their users
        implementation("ch.qos.logback:logback-classic:1.6.5")

        // https://github.com/ajalt/clikt
        implementation("com.github.ajalt.clikt:clikt:5.1.0")

        implementation("io.ktor:ktor-server-core:$ktorVersion")
        implementation("io.ktor:ktor-server-netty:$ktorVersion")
        implementation("io.ktor:ktor-server-compression:$ktorVersion")
        implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")
        testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
        implementation("io.ktor:ktor-server-jte:$ktorVersion")
        implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

        // https://github.com/modelcontextprotocol/kotlin-sdk, MCP server at /mcp
        // 0.15 requires kotlin-stdlib 2.4, which breaks precompileJte (see AGENTS.md)
        implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
        // the MCP SDK depends on older Ktor modules, e.g. ktor-server-sse, align them with the Ktor version of the project
        implementation(platform("io.ktor:ktor-bom:$ktorVersion"))

        // https://mvnrepository.com/artifact/gg.jte/jte
        implementation("gg.jte:jte:3.2.4")

        // https://github.com/snakeyaml/snakeyaml-engine, the file of the custom events of the charts
        implementation("org.snakeyaml:snakeyaml-engine:3.2")
    }

    configurations.runtimeClasspath {
        // Mordant uses the FFM API of Java 22+ to detect the terminal, the JNA and GraalVM backends aren't needed
        exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-jna-jvm")
        exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-graal-ffi-jvm")
        exclude(group = "net.java.dev.jna", module = "jna")
        // only used by Moneta's remote loader of exchange rates, we load the ECB rates ourselves
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }

    jte {
        sourceDirectory.set(file("src/main/resources/templates").toPath())
        targetDirectory.set(project.layout.buildDirectory.file("jte-classes").get().asFile.toPath())
        trimControlStructures.set(true)
        contentType.set(ContentType.Html)

        precompile()
    }

    application {
        mainClass.set("dev.ja.marketplace.ApplicationKt")
        // Netty loads its native transport library, the JVM warns about this without native access
        applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    }

    buildConfig {
        className("BuildConfig")
        packageName("dev.ja.marketplace")
        useKotlinOutput()

        buildConfigField("APP_VERSION", provider { rootDir.resolve("VERSION.txt").readText() })
    }

    tasks {
        build {
            dependsOn(precompileJte)
        }

        jar {
            dependsOn(precompileJte)

            from(precompileJte.map { fileTree(it.targetDirectory) { include("**/*.class") } })
        }

        shadowJar {
            dependsOn(precompileJte)

            archiveBaseName.set("marketplace-stats-all")
            archiveClassifier.set("")
            archiveVersion.set("")

            // allows Netty's native libraries without a warning when started with "java -jar"
            manifest {
                attributes("Enable-Native-Access" to "ALL-UNNAMED")
            }

            from(precompileJte.map { fileTree(it.targetDirectory) { include("**/*.class") } })

            // native libraries of HTTP/3 for all platforms, the server doesn't enable HTTP/3
            exclude("META-INF/native/*netty_quiche*")
            // registers Moneta's loader, which needs the excluded OkHttp
            exclude("META-INF/services/org.javamoney.moneta.spi.loader.LoaderService")

            // removes unused classes of dependencies, but keeps the libraries,
            // which load classes by name or via ServiceLoader
            minimize {
                exclude(dependency("ch.qos.logback:.*:.*"))
                exclude(dependency("io.ktor:.*:.*"))
                exclude(dependency("com.github.ajalt.mordant:.*:.*"))
                exclude(dependency("com.github.ben-manes.caffeine:.*:.*"))
                exclude(dependency("org.javamoney.moneta:.*:.*"))
                exclude(dependency("gg.jte:.*:.*"))
                exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
                exclude(dependency("io.modelcontextprotocol:.*:.*"))
                exclude(dependency("io.github.oshai:.*:.*"))
            }
        }

        // task name run conflicts with Kotlin's run
        named<JavaExec>("run") {
            dependsOn(precompileJte)

            // Shadow complains if a fileTree() or files() is added as a runtimeOnly dependency
            classpath += files(precompileJte.map { it.targetDirectory })
        }
    }
}

interface InjectedArchiveOperations {
    @get:Inject
    val archiveOperations: ArchiveOperations
}

/**
 * Minifies stylesheets into `<name>.min.css`: removes comments, whitespace and the last semicolon of each block. The minifier is
 * conservative, it keeps strings, the spaces of selectors (e.g. `a :hover`) and of values (e.g. `calc(1px + 2px)`).
 */
@CacheableTask
abstract class MinifyCss : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    /**
     * The stylesheets of libraries, which are appended to `main.css` in their order, so that the pages load a single
     * stylesheet, which doesn't block the rendering longer than needed.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val appendedToMain: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun minify() {
        val target = outputDirectory.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        for (file in source.files) {
            val css = when (file.name) {
                "main.css" -> (listOf(file) + appendedToMain.files).joinToString("\n") { it.readText() }
                else -> file.readText()
            }
            target.resolve(file.name.removeSuffix(".css") + ".min.css").writeText(minify(css))
        }
    }

    private fun minify(css: String): String {
        val result = StringBuilder(css.length)
        var index = 0
        var pendingSpace = false
        // characters, after and before which whitespace isn't needed. The space before a colon is kept, it's a
        // descendant combinator of a selector like `a :hover`.
        val noSpaceAfter = "{};,:>"
        val noSpaceBefore = "{};,>"
        fun appendSpace(next: Char) {
            if (pendingSpace && result.isNotEmpty() && result.last() !in noSpaceAfter && next !in noSpaceBefore) {
                result.append(' ')
            }
            pendingSpace = false
        }
        while (index < css.length) {
            val char = css[index]
            when {
                css.startsWith("/*", index) -> {
                    val end = css.indexOf("*/", index + 2)
                    check(end >= 0) { "Unclosed comment in the stylesheet" }
                    index = end + 2
                    pendingSpace = true
                }

                char == '"' || char == '\'' -> {
                    appendSpace(char)
                    var end = index + 1
                    while (end < css.length && css[end] != char) {
                        end += if (css[end] == '\\') 2 else 1
                    }
                    check(end < css.length) { "Unclosed string in the stylesheet" }
                    result.append(css, index, end + 1)
                    index = end + 1
                }

                char.isWhitespace() -> {
                    pendingSpace = true
                    index++
                }

                else -> {
                    // the last declaration of a block doesn't need a semicolon
                    if (char == '}' && result.endsWith(";")) {
                        result.setLength(result.length - 1)
                    }
                    appendSpace(char)
                    result.append(char)
                    index++
                }
            }
        }
        return result.toString()
    }
}

/**
 * Bundles the script [entryPoint] and the modules it imports of [sources] with the Closure Compiler into
 * `js/<bundleName>.min.js` with a source map, which contains the sources. The files are passed when the task runs, so
 * that new and removed modules are noticed, also with the configuration cache.
 */
@CacheableTask
abstract class ClosureBundle : DefaultTask() {
    @get:Input
    abstract val bundleName: Property<String>

    /** The directory of the sources, the paths of the files and of the source map are relative to it. */
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The path of the script, which is loaded by the pages, relative to [sourceDirectory]. */
    @get:Input
    abstract val entryPoint: Property<String>

    @get:Classpath
    abstract val compilerClasspath: ConfigurableFileCollection

    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun bundle() {
        val name = bundleName.get()
        val baseDir = sourceDirectory.get().asFile
        val output = outputDirectory.get().asFile.resolve("js/$name.min.js")
        outputDirectory.get().asFile.deleteRecursively()
        output.parentFile.mkdirs()
        val files = sources.files.map { it.relativeTo(baseDir).invariantSeparatorsPath }.sorted()
        execOperations.javaexec {
            executable(javaLauncher.get().executablePath.asFile)
            classpath(compilerClasspath)
            mainClass.set("com.google.javascript.jscomp.CommandLineRunner")
            // the compiler uses sun.misc.Unsafe, the JDK warns about it
            jvmArgs("--sun-misc-unsafe-memory-access=allow")
            workingDir(baseDir)
            args(
                "--compilation_level", "SIMPLE",
                "--language_in", "ECMASCRIPT_NEXT",
                "--language_out", "NO_TRANSPILE",
                // the JSDoc types aren't checked
                "--warning_level", "QUIET",
                "--dependency_mode", "PRUNE",
                "--entry_point", entryPoint.get(),
                "--js_output_file", output.absolutePath,
                "--create_source_map", "${output.absolutePath}.map",
                "--source_map_include_content",
                // the sources of the source map are shown in the directory of the bundle, e.g. charts/main.js
                "--source_map_location_mapping", "|$name/",
                // the variables of the bundle don't become globals of the page, they're renamed like local variables
                "--assume_function_wrapper",
                "--output_wrapper", "(() => {%output%})();\n//# sourceMappingURL=$name.min.js.map",
            )
            args(files.flatMap { listOf("--js", it) })
        }
    }
}
