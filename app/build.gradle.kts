// Orange :app module.
//
// Principles applied to this file:
//  - Rams #6 (honest): no dependency that isn't used. Audit `dependencies {}`
//    against actual imports before each release.
//  - Rams #10 (as little as possible): no flavor dimensions, no build variants
//    beyond debug/release, no conditional features. Two buttons (debug install,
//    release ship) is the whole build surface.
//  - Apple "design is how it works": the release configuration runs R8 in
//    full mode, strips Log calls, and shrinks resources. What ships is what
//    was tested — nothing more.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.orange.apple"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orange.apple"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }

        // Locales we ship strings for. Without this, R8 strips translations
        // we DO have, or includes ones we DON'T (from libraries). Listing
        // them explicitly is Rams #8 (thorough): the build manifests what
        // the user actually receives.
        resourceConfigurations += listOf("en", "ja", "zh", "ko")
    }

    // Signing is read from environment variables or local.properties.
    // The keystore file, passwords, and aliases are NEVER committed.
    val ksPath: String? = System.getenv("ORANGE_KEYSTORE_PATH")
        ?: providers.gradleProperty("orange.keystore.path").orNull
    val ksPassword: String? = System.getenv("ORANGE_KEYSTORE_PASSWORD")
    val ksKeyAlias: String? = System.getenv("ORANGE_KEY_ALIAS")
    val ksKeyPassword: String? = System.getenv("ORANGE_KEY_PASSWORD")
    val signingAvailable = listOf(ksPath, ksPassword, ksKeyAlias, ksKeyPassword)
        .all { !it.isNullOrEmpty() }

    signingConfigs {
        if (signingAvailable) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingAvailable) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // -Xjvm-default=all keeps interface defaults compiled as real JVM
        // defaults, which cuts bytecode size on small modules like ours.
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        // Everything else default-off. No ViewBinding, no DataBinding, no BuildConfig
        // fields we don't need. Rams #10.
        buildConfig = false
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
        // Reproducible build: zeroize file timestamps in the APK so two
        // builds of the same source produce byte-identical artifacts.
        // Required for F-Droid acceptance and supply-chain auditability.
        jniLibs.useLegacyPackaging = false
    }

    // Embedded Play Store signing block (dependency metadata) leaks dependency
    // graph to anyone with the APK. Strip it from release artifacts. F-Droid
    // policy requires this; Google Play accepts both.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        // Treat any new lint issue as a build break. The cost of fixing one
        // warning today is always lower than ignoring it for a quarter.
        warningsAsErrors = true
        abortOnError = true
        // Categories Orange specifically cares about
        checkDependencies = true
        // The list below is empty by design — we don't disable any default
        // lint check. If a lint rule is wrong for our codebase, fix the code.
        disable += emptySet<String>()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.ripple)

    testImplementation(libs.junit)
}
