import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// The fork root is the upstream tg-ws-proxy checkout. Its proxy/ package is
// copied verbatim into the APK; nothing under it is edited for Android.
val upstreamRoot: File = rootDir.parentFile
val upstreamVersion: String = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(upstreamRoot.resolve("proxy/__init__.py").readText())
    ?.groupValues?.get(1)
    ?: error("Cannot read __version__ from proxy/__init__.py")

// Bump for every Android release; reset the suffix when upstream bumps its version.
val androidRevision = 3

// Release signing, from -Pandmtproto.signing=<keystore.properties> (local builds)
// or ANDROID_KEYSTORE_FILE / _PASSWORD / ANDROID_KEY_ALIAS / _PASSWORD (CI).
// Without either, release builds fall back to the debug key.
val releaseSigning: Map<String, String>? =
    providers.gradleProperty("andmtproto.signing").orNull?.let { path ->
        val props = Properties()
        file(path).inputStream().use { props.load(it) }
        props.stringPropertyNames().associateWith { props.getProperty(it).trim() }
    } ?: providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull?.let { storeFile ->
        fun env(name: String) = providers.environmentVariable(name).orNull
            ?: error("$name must be set together with ANDROID_KEYSTORE_FILE")
        mapOf(
            "storeFile" to storeFile,
            "storePassword" to env("ANDROID_KEYSTORE_PASSWORD"),
            "keyAlias" to env("ANDROID_KEY_ALIAS"),
            "keyPassword" to env("ANDROID_KEY_PASSWORD"),
        )
    }

val upstreamPythonDir = layout.buildDirectory.dir("generated/upstream-python")

val syncUpstreamProxy = tasks.register<Sync>("syncUpstreamProxy") {
    from(upstreamRoot.resolve("proxy")) {
        include("**/*.py")
    }
    into(upstreamPythonDir.map { it.dir("proxy") })
}

android {
    namespace = "io.github.oruviyex18.andmtproto"
    // Compose BOM 2026.09 libraries require compiling against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.oruviyex18.andmtproto"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "$upstreamVersion-android.$androidRevision"

        ndk {
            // Python 3.12+ on Chaquopy is 64-bit only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

chaquopy {
    defaultConfig {
        // Highest version with Android wheels for cryptography (42.0.8) and cffi.
        version = "3.13"
        // Optional: -Pandmtproto.buildPython=/path/to/python3.13 when it isn't on PATH.
        providers.gradleProperty("andmtproto.buildPython").orNull?.let { buildPython(it) }
        pip {
            // Shared with CI's desktop tests (.github/workflows/android.yml).
            install("-r", "requirements.txt")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(upstreamPythonDir.get().asFile)
        }
    }
}

// Chaquopy's per-variant Python tasks read the generated directory.
tasks.configureEach {
    if (name != syncUpstreamProxy.name && name.contains("Python")) {
        dependsOn(syncUpstreamProxy)
    }
}
tasks.named("preBuild") { dependsOn(syncUpstreamProxy) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
