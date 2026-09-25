import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
// 0.1.0: first release. Runs a project's JUnit-XML test suite (Gradle, Maven, pytest),
// shows a pass/fail tree, and exposes test_* MCP tools to in-terminal agents.
version = "0.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// CI sets CI=true and downloads the api jar; locally we compile against the sibling build.
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // The api is compileOnly because the host provides it at runtime, but the tests need it on
    // their own classpath: they exercise the MCP tool definitions, whose types come from it.
    if (useLocalDependencies) {
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.89.jar"))
        testImplementation(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.89.jar"))
    } else {
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
        testImplementation(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

// The loadable plugin JAR: compiled classes + the plugin.json manifest.
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-test-explorer-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Test Explorer Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.testexplorer.TestExplorerDynamicPlugin",
        )
    }
    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// Keep plugin.json's version in lockstep with the build version (single source of truth).
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.build {
    dependsOn("buildPluginJar")
}
