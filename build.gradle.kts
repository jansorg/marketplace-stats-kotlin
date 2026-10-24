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

    dependencies {
        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-datetime-jvm
        // the 0.6.x-compat variant is required by Ktor and would win the conflict resolution anyway
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

        // https://mvnrepository.com/artifact/it.unimi.dsi/fastutil-core
        implementation("it.unimi.dsi:fastutil-core:8.5.19")

        // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
        implementation("ch.qos.logback:logback-classic:1.6.5")

        // https://github.com/ajalt/clikt
        implementation("com.github.ajalt.clikt:clikt:5.1.0")

        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-core
        implementation("org.javamoney.moneta:moneta-core:1.4.5")

        // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
        implementation("com.github.ben-manes.caffeine:caffeine:3.3.1")
        // https://mvnrepository.com/artifact/dev.hsbrysk/caffeine-coroutines
        implementation("dev.hsbrysk:caffeine-coroutines:2.0.4")

        // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
        testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }
}

project("marketplace-client") {
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
    dependencies {
        implementation(project(":marketplace-client"))
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
    }

    val javascriptLibraries by configurations.creating {
        isCanBeConsumed = false
        isTransitive = false
    }

    dependencies {
        // https://www.npmjs.com/package/uplot, used for the charts
        javascriptLibraries("npm:uplot:1.6.32@tgz")
    }

    // Extracts the files of the JavaScript libraries, which are served from the "js" directory of the resources.
    // ArchiveOperations instead of Project.tarTree is compatible with the configuration cache.
    val archives = objects.newInstance<InjectedArchiveOperations>().archiveOperations
    val bundleJavascriptLibraries by tasks.registering(Sync::class) {
        from(javascriptLibraries.elements.map { files -> files.map { archives.tarTree(archives.gzip(it.asFile)) } }) {
            include("package/dist/uPlot.iife.min.js", "package/dist/uPlot.min.css", "package/LICENSE")
            eachFile {
                val fileName = if (name == "LICENSE") "uPlot.LICENSE.txt" else name
                relativePath = RelativePath(true, "js", fileName)
            }
            includeEmptyDirs = false
        }
        into(layout.buildDirectory.dir("generated/javascript"))
    }

    sourceSets.main {
        resources.srcDir(bundleJavascriptLibraries)
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

        implementation("io.ktor:ktor-server-core:$ktorVersion")
        implementation("io.ktor:ktor-server-netty:$ktorVersion")
        implementation("io.ktor:ktor-server-compression:$ktorVersion")
        implementation("io.ktor:ktor-server-jte:$ktorVersion")
        implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

        // https://github.com/modelcontextprotocol/kotlin-sdk, MCP server at /mcp
        // 0.15 requires kotlin-stdlib 2.4, which breaks precompileJte (see AGENTS.md)
        implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
        // the MCP SDK depends on older Ktor modules, e.g. ktor-server-sse, align them with the Ktor version of the project
        implementation(platform("io.ktor:ktor-bom:$ktorVersion"))

        // https://mvnrepository.com/artifact/gg.jte/jte
        implementation("gg.jte:jte:3.2.4")
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
