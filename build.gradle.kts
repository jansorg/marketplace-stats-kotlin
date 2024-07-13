/*
 * Copyright (c) 2023-2024 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import gg.jte.ContentType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// https://mvnrepository.com/artifact/io.ktor/ktor-client-core-jvm
val ktorVersion: String by project

plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"

    // https://github.com/johnrengelman/shadow
    id("com.github.johnrengelman.shadow") version "8.1.1"
    // https://plugins.gradle.org/plugin/gg.jte.gradle
    id("gg.jte.gradle") version "3.1.12"
    // https://github.com/gmazzo/gradle-buildconfig-plugin
    id("com.github.gmazzo.buildconfig") version "5.3.5"
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
        }
    }

    tasks {
        test {
            useJUnitPlatform()
        }
    }

    dependencies {
        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-datetime-jvm
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

        // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

        // https://mvnrepository.com/artifact/it.unimi.dsi/fastutil-core
        implementation("it.unimi.dsi:fastutil-core:8.5.13")

        // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
        implementation("ch.qos.logback:logback-classic:1.5.6")

        // https://github.com/ajalt/clikt
        implementation("com.github.ajalt.clikt:clikt:4.4.0")

        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-core
        implementation("org.javamoney.moneta:moneta-core:1.4.4")
        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-convert
        implementation("org.javamoney.moneta:moneta-convert:1.4.4")
        // https://mvnrepository.com/artifact/org.javamoney.moneta/moneta-convert-ecb
        implementation("org.javamoney.moneta:moneta-convert-ecb:1.4.4")

        // https://mvnrepository.com/artifact/com.google.guava/guava
        implementation("com.google.guava:guava:33.2.1-jre")

        // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
        testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.3")
    }
}

project("marketplace-client") {
    dependencies {
        implementation("io.ktor:ktor-client-core:$ktorVersion")
        implementation("io.ktor:ktor-client-java:$ktorVersion")
        implementation("io.ktor:ktor-client-logging:$ktorVersion")
        implementation("io.ktor:ktor-client-resources:$ktorVersion")
        implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
        implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
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

        // https://mvnrepository.com/artifact/gg.jte/jte
        implementation("gg.jte:jte:3.1.10")

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
