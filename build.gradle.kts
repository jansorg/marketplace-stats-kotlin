/*
 * Copyright (c) 2023-2025 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import gg.jte.ContentType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// https://mvnrepository.com/artifact/io.ktor/ktor-client-core-jvm
val ktorVersion: String by project

plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"

    // https://plugins.gradle.org/plugin/com.gradleup.shadow
    id("com.gradleup.shadow") version "9.0.0-beta4"
    // https://plugins.gradle.org/plugin/gg.jte.gradle
    id("gg.jte.gradle") version "3.1.15"
    // https://github.com/gmazzo/gradle-buildconfig-plugin
    id("com.github.gmazzo.buildconfig") version "5.5.1"
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
        jvmToolchain(21)
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            freeCompilerArgs.add("-Xdebug")
        }
    }

    tasks {
        test {
            useJUnitPlatform()
        }
    }

    dependencies {
        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-datetime-jvm
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

        // https://mvnrepository.com/artifact/it.unimi.dsi/fastutil-core
        implementation("it.unimi.dsi:fastutil-core:8.5.15")

        // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
        implementation("ch.qos.logback:logback-classic:1.5.16")

        // https://github.com/ajalt/clikt
        implementation("com.github.ajalt.clikt:clikt:4.4.0")

        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-core
        implementation("org.javamoney.moneta:moneta-core:1.4.4")
        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-convert
        implementation("org.javamoney.moneta:moneta-convert:1.4.4")
        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-convert-ecb
        implementation("org.javamoney.moneta:moneta-convert-ecb:1.4.4")

        // https://mvnrepository.com/artifact/com.google.guava/guava
        implementation("com.google.guava:guava:33.4.0-jre")

        // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
        implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
        // https://mvnrepository.com/artifact/dev.hsbrysk/caffeine-coroutines
        implementation("dev.hsbrysk:caffeine-coroutines:1.2.1")

        // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
        testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
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
    }
}

project(":marketplace-data") {
    dependencies {
        implementation(project(":marketplace-client"))
    }
}

project(":") {
    dependencies {
        implementation(project(":marketplace-client"))
        implementation(project(":marketplace-data"))

        implementation("io.ktor:ktor-server-core:$ktorVersion")
        implementation("io.ktor:ktor-server-netty:$ktorVersion")
        implementation("io.ktor:ktor-server-compression:$ktorVersion")
        implementation("io.ktor:ktor-server-jte:$ktorVersion")
        implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

        // https://mvnrepository.com/artifact/gg.jte/jte
        implementation("gg.jte:jte:3.1.15")

        runtimeOnly(provider {
            files(tasks.precompileJte.get().targetDirectory)
        })
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
    }

    buildConfig {
        className("BuildConfig")
        packageName("dev.ja.marketplace")
        useKotlinOutput()

        buildConfigField("APP_VERSION", provider { rootDir.resolve("VERSION.txt").readText() })
    }

    tasks {
        precompileJte {
            inputs.files(fileTree("src/main/resources/templates"))
        }

        build {
            dependsOn(precompileJte)
        }

        jar {
            dependsOn(precompileJte)

            from(fileTree(precompileJte.get().targetDirectory) {
                include("**/*.class")
            })
        }

        shadowJar {
            dependsOn(precompileJte)

            archiveBaseName.set("marketplace-stats-all")
            archiveClassifier.set("")
            archiveVersion.set("")
        }

        // task name run conflicts with Kotlin's run
        named("run") {
            dependsOn("precompileJte")
        }
    }
}
