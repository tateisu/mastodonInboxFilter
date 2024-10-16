plugins {
    java
    application
    kotlin("jvm") version Ext.kotlinVersion
    kotlin("plugin.serialization") version Ext.kotlinVersion
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "jp.juggler.mastodonInboxFilter"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "2.3.8"
    val exposedVersion = "0.39.2"

    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.github.xn32:json5k:0.3.0")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jsoup:jsoup:1.17.2")

    runtimeOnly("org.slf4j:slf4j-api:2.0.5")

    testImplementation("org.jetbrains.kotlin:kotlin-test:${Ext.kotlinVersion}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}
