import java.net.URI
import java.net.URISyntaxException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

// Release signing credentials: env vars first, then ~/.gradle/gradle.properties (RNF-03).
// Variables: RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD.
val releaseStoreFile: String? =
    System.getenv("RELEASE_STORE_FILE") ?: providers.gradleProperty("RELEASE_STORE_FILE").orNull
val releaseStorePassword: String? =
    System.getenv("RELEASE_STORE_PASSWORD") ?: providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias: String? =
    System.getenv("RELEASE_KEY_ALIAS") ?: providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword: String? =
    System.getenv("RELEASE_KEY_PASSWORD") ?: providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull

val hasReleaseSigning =
    releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

// API base URL: debug talks to the local Prism mock; release comes from the environment
// (preferred) or ~/.gradle/gradle.properties, outside the repo (RNF-03).
val debugBaseUrl = "http://localhost:4010/"
val releaseBaseUrlPlaceholder = "https://api.invalid/"

fun requireValidBaseUrl(
    name: String,
    value: String,
): String {
    if (value.any { it.isWhitespace() || it == '"' || it == '\\' }) {
        throw GradleException("$name contains characters that are not allowed in a buildConfigField.")
    }
    // Retrofit requires the base URL to end with '/'.
    if (!value.endsWith("/")) {
        throw GradleException("$name must end with '/' for Retrofit.")
    }

    val uri =
        try {
            URI(value)
        } catch (exception: URISyntaxException) {
            // The exception message echoes the full value, so it is deliberately not chained.
            throw GradleException("$name must be a valid absolute URL.")
        }

    // Never allow credentials embedded in the URL: they would leak into build outputs and logs.
    // Checked before the scheme so no earlier failure can hint at any part of the value.
    if (uri.rawUserInfo != null) {
        throw GradleException("$name must not embed userinfo (user:password@host).")
    }
    // Release builds must never send credentials or tokens over cleartext HTTP.
    if (!uri.scheme.orEmpty().equals("https", ignoreCase = true)) {
        throw GradleException("$name must use https:// in release builds.")
    }
    if (uri.host.isNullOrEmpty()) {
        throw GradleException("$name must include a non-empty host.")
    }
    // URI reports -1 when no port was supplied. Anything else must be a valid TCP port.
    if (uri.port != -1 && uri.port !in 1..65535) {
        throw GradleException("$name must use a port between 1 and 65535.")
    }
    // `/v1/auth/` endpoints are resolved from the root, so a path prefix, query or fragment would
    // silently change where requests land. The value is never echoed (RNF-14).
    val rawPath = uri.rawPath
    if (rawPath != null && rawPath != "" && rawPath != "/") {
        throw GradleException("$name must not include a path; API endpoints are resolved from the root.")
    }
    if (uri.rawQuery != null) {
        throw GradleException("$name must not include a query string.")
    }
    if (uri.rawFragment != null) {
        throw GradleException("$name must not include a fragment.")
    }
    return value
}

val releaseBaseUrlFromEnvironment: String? =
    providers.environmentVariable("BUZZLEARN_BASE_URL").orNull
val releaseBaseUrlFromProperty: String? =
    providers.gradleProperty("BUZZLEARN_BASE_URL").orNull
val configuredReleaseBaseUrl: String? =
    releaseBaseUrlFromEnvironment
        ?.takeIf { it.isNotBlank() }
        ?: releaseBaseUrlFromProperty?.takeIf { it.isNotBlank() }
val releaseBaseUrl: String =
    configuredReleaseBaseUrl
        ?.let { requireValidBaseUrl("BUZZLEARN_BASE_URL", it) }
        ?: releaseBaseUrlPlaceholder

android {
    namespace = "com.buzzness.buzzlearn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.buzzness.buzzlearn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // Physical device via `adb reverse tcp:4010 tcp:4010`. Emulators would use
            // 10.0.2.2, but this repo does not use emulators (ADR-002).
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

if (!hasReleaseSigning) {
    logger.warn(
        "Release signing is not configured (RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / " +
            "RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD). assembleRelease will produce an " +
            "unsigned APK, which Play Console will reject (use assembleDebug for CI validation).",
    )
    afterEvaluate {
        tasks.named("bundleRelease") {
            doFirst {
                throw GradleException(
                    "Release signing credentials missing (RELEASE_STORE_FILE/RELEASE_STORE_PASSWORD/" +
                        "RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD). assembleRelease stays unsigned for " +
                        "CI validation.",
                )
            }
        }
    }
}

if (configuredReleaseBaseUrl == null) {
    logger.warn(
        "BUZZLEARN_BASE_URL is not set. assembleRelease uses the non-routable placeholder " +
            "$releaseBaseUrlPlaceholder for CI validation (R8 + manifest); bundleRelease " +
            "fails closed. Set BUZZLEARN_BASE_URL as an env var or in ~/.gradle/gradle.properties.",
    )
    afterEvaluate {
        tasks.named("bundleRelease") {
            doFirst {
                throw GradleException(
                    "BUZZLEARN_BASE_URL is not set. bundleRelease fails closed; configure it " +
                        "via an environment variable (preferred) or ~/.gradle/gradle.properties.",
                )
            }
        }
    }
}

ktlint {
    version.set("1.7.0")
}

dependencies {
    constraints {
        implementation("com.squareup.okhttp3:okhttp") {
            version { strictly("5.5.1") }
        }
        implementation("com.squareup.okhttp3:okhttp-android") {
            version { strictly("5.5.1") }
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
