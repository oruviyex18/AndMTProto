import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// The fork root is the upstream tg-ws-proxy checkout. Its proxy/ package is
// copied verbatim into the APK; nothing under it is edited for Android.
val upstreamRoot: File = rootDir.parentFile
val upstreamVersion: String = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(upstreamRoot.resolve("proxy/__init__.py").readText())
    ?.groupValues?.get(1)
    ?: error("Cannot read __version__ from proxy/__init__.py")

// Bump for every Android release; reset the suffix when upstream bumps its version.
val androidRevision = 2

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
        versionCode = 2
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
            install("cryptography==42.0.8")
            install("httpx[http2]==0.28.1")
            install("certifi")
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
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
