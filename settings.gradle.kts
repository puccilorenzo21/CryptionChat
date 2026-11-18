pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // --- QUESTA RIGA È OBBLIGATORIA PER LE LIBRERIE DI EDITING ---
        maven { url = uri("https://jitpack.io") }
        // -------------------------------------------------------------
    }
}

rootProject.name = "CryptionChat"
include(":app")