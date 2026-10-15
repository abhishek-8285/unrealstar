plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unrealstar.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unrealstar.live"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // The dev token lives in a build-type field rather than a Kotlin const:
        // a `const val` is inlined at compile time and the literal ends up in the
        // release DEX even behind a runtime BuildConfig.DEBUG check.
        debug {
            buildConfigField("String", "DEV_AUTH_TOKEN", "\"guest_token_1001\"")
            buildConfigField("long", "DEV_USER_ID", "1001L")
            // Defaults to the adb-reverse tunnel; override to point a debug
            // build at a deployed origin: -PapiBaseUrl=https://host/api/v1
            val debugBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "http://127.0.0.1:3000/api/v1"
            buildConfigField("String", "API_BASE_URL", "\"$debugBaseUrl\"")
        }
        release {
            buildConfigField("String", "DEV_AUTH_TOKEN", "\"\"")
            buildConfigField("long", "DEV_USER_ID", "0L")
            // Public origin behind Caddy/TLS. Override per build with
            // -PapiBaseUrl=... when staging elsewhere.
            val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "https://us.avandab.com/api/v1"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // AGP 8 no longer generates BuildConfig unless asked; SessionStore needs
        // BuildConfig.DEBUG to keep the dev token out of release builds.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // Real-time publishing (Phase 1 next step): WebRTC publisher/subscriber.
    // Pinned to 1.0.43577: 1.0.45036's PeerConnectionFactory$Builder references
    // org.webrtc.Environment, which its own classes.jar omits -> runtime
    // NoClassDefFoundError on device (verified 2026-10-15). 43577's Builder
    // has zero Environment references (javap-verified).
    implementation("com.infobip:google-webrtc:1.0.43577")
    // Luxury full-screen gift effects (Phase 1 next step): PAG player.
    implementation("com.tencent.tav:libpag:4.5.98-noffavc")

    // Video playback for the Home feed
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    // Disk-backed media cache (SimpleCache / CacheDataSource).
    implementation("androidx.media3:media3-datasource:$media3Version")

    // Renders cover_url and author_avatar_url, which the feed API now returns.
    implementation("com.github.bumptech.glide:glide:5.0.5")

    // Networking & WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Face tracking for AR stickers (throttled ML Kit, graceful-off without GMS)
    implementation("com.google.mlkit:face-detection:16.1.7")

    testImplementation("junit:junit:4.13.2")
}
