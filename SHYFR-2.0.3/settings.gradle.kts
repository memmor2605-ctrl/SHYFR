pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // libsignal publikuje artefakty we własnym repozytorium Maven (tylko grupa org.signal)
        maven { url = uri("https://build-artifacts.signal.org/libraries/maven/"); content { includeGroup("org.signal") } }
    }
}
rootProject.name = "SHYFR"
include(":app")
