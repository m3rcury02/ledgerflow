pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ledgerflow"

include("application")
include("modules:ledger")
include("modules:messaging")
include("modules:notifications")
include("modules:operations")
include("modules:orders")
include("modules:payments")
