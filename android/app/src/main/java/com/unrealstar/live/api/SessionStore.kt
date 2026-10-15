package com.unrealstar.live.api

import com.unrealstar.live.BuildConfig

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

    private const val PREFS = "unrealstar_session";
    private const val KEY_TOKEN = "auth_token";
    private const val KEY_USER_ID = "user_id";

    @Volatile
    private var token: String? = null

    @Volatile
    var userId: Long = 0
        private set

    val isSignedIn: Boolean
        get() = !token.isNullOrBlank()

    /** Empty when signed out; the API then answers 401. */
    fun authorization(): String = token?.let { "Bearer $it" } ?: ""

    /** Raw token for carriers that cannot use an Authorization header (WS `?token=`). */
    fun rawToken(): String? = token

    /** Must be called once from Application/Activity before use. */
    fun init(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        userId = prefs.getLong(KEY_USER_ID, 0L)
        if (token == null) {
            val devToken = BuildConfig.DEV_AUTH_TOKEN
            if (devToken.isNotBlank()) {
                token = devToken
                userId = BuildConfig.DEV_USER_ID
            }
        }
    }

    fun signIn(context: android.content.Context, newToken: String, newUserId: Long) {
        signIn(newToken, newUserId)
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, newToken)
            .putLong(KEY_USER_ID, newUserId)
            .apply()
    }

    fun signIn(newToken: String, newUserId: Long) {
        require(newToken.isNotBlank()) { "token must not be blank" }
        token = newToken
        userId = newUserId
    }

    fun signOut(context: android.content.Context? = null) {
        token = null
        userId = 0
        context?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            ?.edit()?.clear()?.apply()
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