plugins {
    id("java")
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.ceasle.intellicursor"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
    }

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.ceasle.intellicursor"
        version = "1.0.0"
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "263.*"
        }
        changeNotes = """
            <p>First release.</p>
            <ul>
              <li>Chat with the Cursor agent in an IntelliJ tool window, with live progress for reasoning, tool calls and plans.</li>
              <li>Agent, Plan and Ask modes, and any model your Cursor account offers.</li>
              <li>Attach project files with <code>@</code>; slash commands including your Cursor skills and custom commands.</li>
              <li>Approve or deny file edits and shell commands, or turn on Run everything.</li>
              <li>Chat history, MCP server management and Cursor sign-in from the panel.</li>
              <li>Editor actions to send, explain or refactor selected code, and review the agent's changes in the diff viewer.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
