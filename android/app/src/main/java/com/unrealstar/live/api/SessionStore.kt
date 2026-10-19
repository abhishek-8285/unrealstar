package com.unrealstar.live.api

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.unrealstar.live.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the bearer token used for every API call.
 *
 * Tokens live in Keystore-backed EncryptedSharedPreferences (AES256_GCM);
 * if the Keystore is broken on a device we fall back to MODE_PRIVATE
 * rather than crash-looping (logged). Both access and refresh tokens are
 * persisted so sessions survive process death; the refresh token rotates
 * server-side on every use.
 *
 * A release build starts signed out: requests go out unauthenticated and
 * fail loudly instead of silently shipping the development guest token
 * inside the APK, where anyone can pull it out of the binary. Sign in via
 * the OTP screen ([OtpLoginActivity]) and the rest of the app works.
 */
// Lint cannot see that [appCtx] is always the application context, which
// lives as long as the process — no Activity leak is possible here.
@android.annotation.SuppressLint("StaticFieldLeak")
object SessionStore {

    private const val PREFS = "unrealstar_session"
    private const val PREFS_PLAIN_FALLBACK = "unrealstar_session"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DEVICE_UID = "device_uid"
    private const val TAG = "SessionStore"

    @Volatile
    private var token: String? = null

    @Volatile
    private var refreshToken: String? = null

    @Volatile
    private var cachedDeviceUid: String? = null

    @Volatile
    var userId: Long = 0
        private set

    val isSignedIn: Boolean
        get() = !token.isNullOrBlank()

    /** Empty when signed out; the API then answers 401. */
    fun authorization(): String = token?.let { "Bearer $it" } ?: ""

    /** Raw token for carriers that cannot use an Authorization header (WS `?token=`). */
    fun rawToken(): String? = token

    /** Best-effort snapshot for server logout. Null when signed out. */
    internal fun refreshTokenSnapshot(): String? = refreshToken

    private fun prefs(context: Context): SharedPreferences {
        return try {
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS,
                androidx.security.crypto.MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "encrypted prefs unavailable, falling back to private prefs", t)
            context.applicationContext.getSharedPreferences(
                PREFS_PLAIN_FALLBACK, Context.MODE_PRIVATE
            )
        }
    }

    /** Per-install device id, stable across launches. Never a debug constant. */
    fun deviceUid(context: Context): String {
        cachedDeviceUid?.let { return it }
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_DEVICE_UID, null)?.takeIf { it.isNotBlank() }
        val uid = existing ?: java.util.UUID.randomUUID().toString()
        if (existing == null) {
            try { prefs.edit { putString(KEY_DEVICE_UID, uid) } } catch (_: Throwable) {}
        }
        cachedDeviceUid = uid
        return uid
    }

    /** Must be called once from Application/Activity before use. */
    fun init(context: Context) {
        appCtx = context.applicationContext
        val prefs = prefs(context.applicationContext)
        token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        refreshToken = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }
        userId = prefs.getLong(KEY_USER_ID, 0L)
        cachedDeviceUid = prefs.getString(KEY_DEVICE_UID, null)?.takeIf { it.isNotBlank() }
            ?: deviceUid(context.applicationContext)
        if (token == null) {
            val devToken = BuildConfig.DEV_AUTH_TOKEN
            if (devToken.isNotBlank()) {
                token = devToken
                userId = BuildConfig.DEV_USER_ID
            }
        }
    }

    /** Full OTP sign-in: persists access + refresh tokens. */
    fun signIn(
        context: Context,
        accessToken: String,
        newRefreshToken: String,
        newUserId: Long
    ) {
        require(accessToken.isNotBlank()) { "token must not be blank" }
        token = accessToken
        refreshToken = newRefreshToken.takeIf { it.isNotBlank() }
        userId = newUserId
        try {
            prefs(context.applicationContext).edit {
                putString(KEY_TOKEN, accessToken)
                putString(KEY_REFRESH, refreshToken)
                putLong(KEY_USER_ID, newUserId)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "persisting session failed", t)
        }
    }

    fun signIn(newToken: String, newUserId: Long) {
        require(newToken.isNotBlank()) { "token must not be blank" }
        token = newToken
        userId = newUserId
    }

    /**
     * Single-flight access-token refresh (server rotates the pair on every
     * use). Called by the HTTP authenticator on 401. Returns the fresh
     * access token, or null when refresh is impossible (signed out, dead
     * refresh token, offline) — the caller then surfaces the original 401.
     */
    private val refreshMutex = Mutex()
    private val refreshGate = Any()
    @Volatile private var lastRefreshAttemptMs: Long = 0L

    /** Application context cached at init for background persistence. */
    @Volatile private var appCtx: Context? = null

    suspend fun refreshAccessToken(): String? {
        val now = SystemClock.elapsedRealtime()
        synchronized(refreshGate) {
            // A sibling request just refreshed (common 401 burst): reuse it.
            if (now - lastRefreshAttemptMs < 10_000 && !token.isNullOrBlank()) {
                return token
            }
            lastRefreshAttemptMs = now
        }
        return refreshMutex.withLock {
            val rt = refreshToken ?: return null
            val device = cachedDeviceUid ?: return null
            val res = UnrealStarApiClient.performTokenRefresh(rt, device)
            val t = res.getOrNull() ?: return null
            token = t.access_token
            refreshToken = t.refresh_token.takeIf { it.isNotBlank() }
            userId = t.user_id
            // Best-effort persist (cached app context from init).
            lastPersistedTokens(t.access_token, t.refresh_token, t.user_id)
            t.access_token
        }
    }

    private fun lastPersistedTokens(access: String, refresh: String, uid: Long) {
        val ctx = appCtx ?: return
        try {
            prefs(ctx).edit {
                putString(KEY_TOKEN, access)
                putString(KEY_REFRESH, refresh)
                putLong(KEY_USER_ID, uid)
            }
        } catch (_: Throwable) {}
    }

    fun signOut(context: Context? = null) {
        token = null
        refreshToken = null
        userId = 0
        // Scoped removal: never .clear() — unrelated flags (push toggle,
        // onboarding, cache prefs) must survive logout.
        try {
            (context?.applicationContext ?: appCtx)?.let { ctx ->
                prefs(ctx).edit {
                    remove(KEY_TOKEN)
                    remove(KEY_REFRESH)
                    remove(KEY_USER_ID)
                }
            }
        } catch (_: Throwable) {}
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
