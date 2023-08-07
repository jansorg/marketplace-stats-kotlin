/*
 * Copyright (c) 2023 Joachim Ansorg.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

val ktorVersion: String by project

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
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
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0-RC")

        implementation("org.apache.logging.log4j:log4j-core:2.20.0")
        implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.20.0")

        testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.3")
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

        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
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

        implementation("gg.jte:jte-kotlin:3.0.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    }

    application {
        mainClass.set("dev.ja.marketplace.Application")
    }

    tasks {
        shadowJar {
            archiveBaseName.set("marketplace-client-all")
            archiveClassifier.set("")
            archiveVersion.set("")
        }
    }
}
