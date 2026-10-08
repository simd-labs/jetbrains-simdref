plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.simdlabs"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Publishing prep: all four credentials come from the environment, never the repo.
val publishToken = providers.environmentVariable("PUBLISH_TOKEN")
val certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
val privateKey = providers.environmentVariable("PRIVATE_KEY")
val privateKeyPassword = providers.environmentVariable("PRIVATE_KEY_PASSWORD")

dependencies {
    intellijPlatform {
        clion("2026.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
        vendor {
            url = "https://github.com/simd-labs"
        }
    }
    signing {
        this.certificateChain = certificateChain.orNull
        this.privateKey = privateKey.orNull
        password = privateKeyPassword.orNull
    }
    publishing {
        token = publishToken.orNull
    }
    pluginVerification {
        ides {
            create("CL", "2026.1")
            create("IU", "2026.1")
            create("CL", "2026.2") { useInstaller = false }
            create("IU", "2026.2") { useInstaller = false }
        }
    }
}
