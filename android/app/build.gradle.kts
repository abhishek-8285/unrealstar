plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unrealstar.live"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.unrealstar.live"
        minSdk = 26
        targetSdk = 36
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
            // Now defaults to the deployed origin; point a debug build back at
            // the adb-reverse tunnel with: -PapiBaseUrl=http://127.0.0.1:3000/api/v1
            val debugBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "https://us.avandab.com/api/v1"
            buildConfigField("String", "API_BASE_URL", "\"$debugBaseUrl\"")
            // TURN relay for symmetric-NAT viewers (STUN alone cannot punch
            // those). Empty by default = STUN-only local dev. Deploy with
            // -PturnUrl=turn:host:3478 -PturnUser=u -PturnPass=p.
            // A buildsystem-injected secret still ships in the APK; prefer a
            // short-lived credential endpoint before public release.
            val turnUrl = (project.findProperty("turnUrl") as String?) ?: ""
            val turnUser = (project.findProperty("turnUser") as String?) ?: ""
            val turnPass = (project.findProperty("turnPass") as String?) ?: ""
            buildConfigField("String", "TURN_URL", "\"$turnUrl\"")
            buildConfigField("String", "TURN_USER", "\"$turnUser\"")
            buildConfigField("String", "TURN_PASS", "\"$turnPass\"")
            // Runtime filter pack (GradePackLoader). Blank = off. Debug dev
            // loop: -PgradePackUrl=http://127.0.0.1:9373/grade_pack.zip with
            // `adb reverse tcp:9373 tcp:9373` + `python3 -m http.server 9373
            // --directory rea-out/grade-pack`. Cleartext loopback is
            // whitelisted in network_security_config.xml.
            val gradePackUrl = (project.findProperty("gradePackUrl") as String?) ?: ""
            buildConfigField("String", "GRADE_PACK_URL", "\"$gradePackUrl\"")
        }
        release {
            buildConfigField("String", "DEV_AUTH_TOKEN", "\"\"")
            buildConfigField("long", "DEV_USER_ID", "0L")
            // Public origin behind Caddy/TLS. Override per build with
            // -PapiBaseUrl=... when staging elsewhere.
            val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
                ?: "https://us.avandab.com/api/v1"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            val turnUrl = (project.findProperty("turnUrl") as String?) ?: ""
            val turnUser = (project.findProperty("turnUser") as String?) ?: ""
            val turnPass = (project.findProperty("turnPass") as String?) ?: ""
            buildConfigField("String", "TURN_URL", "\"$turnUrl\"")
            buildConfigField("String", "TURN_USER", "\"$turnUser\"")
            buildConfigField("String", "TURN_PASS", "\"$turnPass\"")
            // Runtime filter pack stays off in release until the backend
            // endpoint ships; enable per build with -PgradePackUrl=https://...
            val gradePackUrl = (project.findProperty("gradePackUrl") as String?) ?: ""
            buildConfigField("String", "GRADE_PACK_URL", "\"$gradePackUrl\"")
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    // Pull-to-refresh on the Inbox lists, like the reference.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")

    // CameraX
    val cameraxVersion = "1.6.2"
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
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    // Disk-backed media cache (SimpleCache / CacheDataSource).
    implementation("androidx.media3:media3-datasource:$media3Version")

    // Renders cover_url and author_avatar_url, which the feed API now returns.
    // 5.0.9 requires compileSdk 37 (AGP 9.1); held at the latest SDK-36 build.
    implementation("com.github.bumptech.glide:glide:5.0.5")

    // Networking & WebSocket
    // OkHttp 5.x requires compileSdk 37; held on the 4.12 line for SDK 36.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.14.0")

    // Encrypted token storage (Keystore-backed, AES256_GCM).
    implementation("androidx.security:security-crypto:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Face tracking for AR stickers (throttled ML Kit, graceful-off without GMS)
    implementation("com.google.mlkit:face-detection:16.1.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
