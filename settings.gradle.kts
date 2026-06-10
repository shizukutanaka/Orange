// Apple philosophy: make it build. A codebase that can't be compiled by an
// outside reviewer is not auditable, and an un-auditable codebase cannot make
// the privacy claims that define this product.
//
// Single module. No meta-project scaffolding. Version Catalog for all deps.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "orange"
include(":app")
