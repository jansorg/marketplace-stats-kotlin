/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import gg.jte.ContentType

val ktorVersion: String by project

plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"

    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("gg.jte.gradle") version "3.1.6"
}

allprojects {
    repositories {
        mavenCentral()
    }

    apply {
        plugin("kotlin")
        plugin("org.jetbrains.kotlin.plugin.serialization")
    }

    dependencies {
        // https://mvnrepository.com/artifact/it.unimi.dsi/fastutil-core
        implementation("it.unimi.dsi:fastutil-core:8.5.12")

        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

        implementation("org.apache.logging.log4j:log4j-core:2.20.0")
        implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.20.0")

        testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    }

    tasks {
        test {
            useJUnitPlatform()
        }
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

        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
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

        implementation("gg.jte:jte:3.1.6")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

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
        mainClass.set("dev.ja.marketplace.Application")
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
