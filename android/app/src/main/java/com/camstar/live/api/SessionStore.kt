package com.camstar.live.api

import com.camstar.live.BuildConfig

/**
 * Holds the bearer token used for every API call.
 *
 * There is no sign-in screen yet, so a release build starts signed out and every
 * request goes out unauthenticated. That fails loudly instead of silently
 * shipping the development guest token inside the APK, where anyone can pull it
 * out of the binary. Wire an OTP screen (POST /api/v1/auth/otp/verify) to
 * [signIn] and the rest of the app starts working unchanged.
 */
object SessionStore {


    @Volatile
    private var token: String? = null

    @Volatile
    var userId: Long = 0
        private set

    val isSignedIn: Boolean
        get() = !token.isNullOrBlank()

    /** Empty when signed out; the API then answers 401. */
    fun authorization(): String = token?.let { "Bearer $it" } ?: ""

    fun signIn(newToken: String, newUserId: Long) {
        require(newToken.isNotBlank()) { "token must not be blank" }
        token = newToken
        userId = newUserId
    }

    fun signOut() {
        token = null
        userId = 0
    }

    init {
        // Debug-only convenience so `assembleDebug` works with no login step.
        // The release build compiles this field to an empty string, so the
        // credential is not present in the shipped artifact at all.
        val devToken = BuildConfig.DEV_AUTH_TOKEN
        if (devToken.isNotBlank()) {
            signIn(devToken, BuildConfig.DEV_USER_ID)
        }
    }
}