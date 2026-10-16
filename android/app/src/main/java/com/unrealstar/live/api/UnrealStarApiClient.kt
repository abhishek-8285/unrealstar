package com.unrealstar.live.api

import com.unrealstar.live.BuildConfig
import com.unrealstar.live.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import org.json.JSONObject

object UnrealStarApiClient {
    // Over ADB reverse or emulator, localhost:3000 reaches the Axum backend
    /** Debug talks to the device-local reverse tunnel; release to the public origin. */
    private val BASE_URL = BuildConfig.API_BASE_URL

    /**
     * Rewrites an absolute media URL onto whichever host this build talks to.
     * The API returns fully-qualified asset URLs, which would otherwise pin the
     * device to the public origin even in a build aimed at localhost.
     */
    fun localise(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return url
        return BASE_URL.substringBefore("/api/v1") + path
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(okhttp3.logging.HttpLoggingInterceptor().apply {
                    level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()
    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchWalletBalance(): WalletBalance = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/wallet/balance")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("wallet/balance HTTP ${response.code}")
            }
            val body = response.body?.string() ?: ""
            gson.fromJson(body, WalletBalance::class.java)
        }
    }


    /**
     * One page of the feed. Pass the previous page's `next_cursor` to advance;
     * null means the end of the feed.
     */
    /** Cheap call used to tell "signed out" apart from "server unreachable". */
    enum class AuthState { SignedIn, SignedOut, Unreachable }

    suspend fun probeAuth(): AuthState = withContext(Dispatchers.IO) {
        if (!SessionStore.isSignedIn) return@withContext AuthState.SignedOut
        val request = Request.Builder()
            .url("$BASE_URL/wallet/balance")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> AuthState.SignedOut
                    response.isSuccessful -> AuthState.SignedIn
                    else -> AuthState.Unreachable
                }
            }
        } catch (e: IOException) {
            AuthState.Unreachable
        }
    }

    suspend fun fetchFeed(
        cursor: String? = null,
        limit: Int = 10,
        followedOnly: Boolean = false,
    ): FeedPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$BASE_URL/feed/moments?limit=")
            append(limit)
            if (followedOnly) {
                append("&followed_only=true")
            }
            if (!cursor.isNullOrBlank()) {
                append("&cursor=")
                append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", SessionStore.authorization())
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext FeedPage()
                    }
                    val body = response.body?.string() ?: ""
                    gson.fromJson(body, FeedPage::class.java) ?: FeedPage()
                }
            } catch (e: IOException) {
                FeedPage()
            }
        }

    suspend fun createMoment(req: CreateMomentRequest): Result<FeedPost> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("media_url", req.media_url)
            put("caption", req.caption)
            put("duration_secs", req.duration_secs)
            if (req.promo_text != null) put("promo_text", req.promo_text)
            if (req.cover_url != null) put("cover_url", req.cover_url)
            val tagsArr = org.json.JSONArray()
            req.tags.forEach { tagsArr.put(it) }
            put("tags", tagsArr)
        }
        val request = Request.Builder()
            .url("$BASE_URL/feed/moments/create")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val body = response.body?.string() ?: "{}"
                val obj = JSONObject(body)
                val post = FeedPost(
                    moment_id = obj.optString("moment_id"),
                    author_id = obj.optLong("author_id"),
                    author_nickname = obj.optString("author_nickname"),
                    author_avatar_url = obj.optString("avatar_url"),
                    media_url = obj.optString("media_url"),
                    cover_url = obj.optString("cover_url"),
                    caption = obj.optString("caption"),
                    duration_secs = obj.optInt("duration_secs", 15),
                    likes_count = obj.optLong("likes_count", 0L),
                    comment_count = obj.optLong("comment_count", 0L),
                    share_count = obj.optLong("share_count", 0L),
                    gift_count = obj.optLong("gift_count", 0L)
                )
                Result.success(post)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchRooms(tab: String, page: Int): RoomPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/feed/rooms?tab=$tab&page=$page")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext RoomPage()
                val body = response.body?.string() ?: ""
                gson.fromJson(body, RoomPage::class.java) ?: RoomPage()
            }
        } catch (e: IOException) {
            RoomPage()
        }
    }

    suspend fun fetchOfficialNotices(section: String?): Result<OfficialNoticePage> =
        withContext(Dispatchers.IO) {
            val url = if (section == null) "$BASE_URL/inbox/official"
            else "$BASE_URL/inbox/official?section=$section"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", SessionStore.authorization())
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(RuntimeException("HTTP ${response.code}"))
                    }
                    val page = gson.fromJson(response.body?.string() ?: "", OfficialNoticePage::class.java)
                        ?: OfficialNoticePage()
                    Result.success(page)
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    suspend fun fetchMeDashboard(): MeDashboard = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/users/me/dashboard")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext MeDashboard()
                gson.fromJson(response.body?.string() ?: "", MeDashboard::class.java)
                    ?: MeDashboard()
            }
        } catch (e: IOException) {
            MeDashboard()
        }
    }

    suspend fun fetchInboxCategories(): InboxCategoryPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/inbox/categories")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext InboxCategoryPage()
                gson.fromJson(response.body?.string() ?: "", InboxCategoryPage::class.java)
                    ?: InboxCategoryPage()
            }
        } catch (e: IOException) {
            InboxCategoryPage()
        }
    }

    suspend fun fetchInbox(category: String?): InboxMessagePage = withContext(Dispatchers.IO) {
        val url = if (category == null) "$BASE_URL/inbox"
        else "$BASE_URL/inbox?category=$category"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext InboxMessagePage()
                gson.fromJson(response.body?.string() ?: "", InboxMessagePage::class.java)
                    ?: InboxMessagePage()
            }
        } catch (e: IOException) {
            InboxMessagePage()
        }
    }

    suspend fun fetchLiveBanners(): LiveBannerPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/feed/live/banners")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext LiveBannerPage()
                val body = response.body?.string() ?: ""
                gson.fromJson(body, LiveBannerPage::class.java) ?: LiveBannerPage()
            }
        } catch (e: IOException) {
            LiveBannerPage()
        }
    }

    suspend fun fetchComments(momentId: String): CommentList = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/feed/moments/$momentId/comments")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CommentList()
                val body = response.body?.string() ?: ""
                gson.fromJson(body, CommentList::class.java) ?: CommentList()
            }
        } catch (e: IOException) {
            CommentList()
        }
    }

    suspend fun postComment(
        momentId: String,
        body: String,
        parentId: String? = null,
    ): Result<MomentEngagement> = withContext(Dispatchers.IO) {
        val payload = gson
            .toJson(linkedMapOf("body" to body, "parent_id" to parentId))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/feed/moments/$momentId/comments")
            .header("Authorization", SessionStore.authorization())
            .post(payload)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException(response.body?.string() ?: "Failed")
                    )
                }
                Result.success(
                    gson.fromJson(response.body?.string() ?: "", MomentEngagement::class.java)
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unlikeMoment(momentId: String): Result<MomentEngagement> =
        postEmpty("$BASE_URL/feed/moments/$momentId/unlike")

    suspend fun likeComment(commentId: String): Result<CommentLikeResponse> =
        commentEngagement("$BASE_URL/feed/comments/$commentId/like")

    suspend fun unlikeComment(commentId: String): Result<CommentLikeResponse> =
        commentEngagement("$BASE_URL/feed/comments/$commentId/unlike")

    private suspend fun commentEngagement(url: String): Result<CommentLikeResponse> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", SessionStore.authorization())
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException(response.body?.string() ?: "Request failed")
                        )
                    }
                    Result.success(
                        gson.fromJson(response.body?.string() ?: "", CommentLikeResponse::class.java)
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun postEmpty(url: String): Result<MomentEngagement> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", SessionStore.authorization())
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException(response.body?.string() ?: "Request failed")
                        )
                    }
                    Result.success(
                        gson.fromJson(response.body?.string() ?: "", MomentEngagement::class.java)
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Records one engagement action and returns the server's full counter
     * snapshot, so every rail number can be reconciled from one response
     * instead of guessing at the new local value.
     */
    private suspend fun bumpMoment(
        momentId: String,
        action: String,
    ): Result<MomentEngagement> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/feed/moments/$momentId/$action")
            .header("Authorization", SessionStore.authorization())
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Request failed"
                    return@withContext Result.failure(IOException(err))
                }
                val body = response.body?.string() ?: ""
                Result.success(gson.fromJson(body, MomentEngagement::class.java))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun likeMoment(momentId: String): Result<MomentEngagement> =
        bumpMoment(momentId, "like")

    suspend fun shareMoment(momentId: String): Result<MomentEngagement> =
        bumpMoment(momentId, "share")

    suspend fun giftMoment(momentId: String): Result<MomentEngagement> =
        bumpMoment(momentId, "gift")

    suspend fun sendGift(req: SendGiftRequest): Result<SendGiftResponse> = withContext(Dispatchers.IO) {
        val json = gson.toJson(req)
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/gifts/send")
            .header("Authorization", SessionStore.authorization())
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Gifting failed"
                    return@withContext Result.failure(IOException(err))
                }
                val resBody = response.body?.string() ?: ""
                val giftRes = gson.fromJson(resBody, SendGiftResponse::class.java)
                Result.success(giftRes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchGiftCatalog(): Result<List<com.unrealstar.live.models.GiftCatalogItem>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/gifts/catalog")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val listType = object : com.google.gson.reflect.TypeToken<List<com.unrealstar.live.models.GiftCatalogItem>>() {}.type
                val catalog: List<com.unrealstar.live.models.GiftCatalogItem> = gson.fromJson(response.body?.string() ?: "[]", listType) ?: emptyList()
                Result.success(catalog)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun spinWheel(req: SpinWheelRequest): Result<SpinWheelResponse> = withContext(Dispatchers.IO) {

        val json = gson.toJson(req)
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/games/turntable/spin")
            .header("Authorization", SessionStore.authorization())
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Spin failed"
                    return@withContext Result.failure(IOException(err))
                }
                val resBody = response.body?.string() ?: ""
                val spinRes = gson.fromJson(resBody, SpinWheelResponse::class.java)
                Result.success(spinRes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun digShovel(req: ShovelDigRequest): Result<ShovelDigResponse> = withContext(Dispatchers.IO) {
        val json = gson.toJson(req)
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/games/shovel/dig")
            .header("Authorization", SessionStore.authorization())
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Shovel dig failed"
                    return@withContext Result.failure(IOException(err))
                }
                val resBody = response.body?.string() ?: ""
                val shovelRes = gson.fromJson(resBody, ShovelDigResponse::class.java)
                Result.success(shovelRes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun wishMagicLamp(req: MagicLampWishRequest): Result<MagicLampWishResponse> = withContext(Dispatchers.IO) {
        val json = gson.toJson(req)
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$BASE_URL/games/magiclamp/wish")
            .header("Authorization", SessionStore.authorization())
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "Magic lamp wish failed"
                    return@withContext Result.failure(IOException(err))
                }
                val resBody = response.body?.string() ?: ""
                val wishRes = gson.fromJson(resBody, MagicLampWishResponse::class.java)
                Result.success(wishRes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** OTP login step 1: send code. Debug builds echo debug_otp. */
    suspend fun otpSend(phoneNumber: String): Result<OtpSendResponse> = withContext(Dispatchers.IO) {
        val json = gson.toJson(mapOf("phone_number" to phoneNumber, "country_code" to "+91"))
        val request = Request.Builder()
            .url("$BASE_URL/auth/otp/send")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(response.body?.string() ?: "OTP send failed"))
                }
                Result.success(gson.fromJson(response.body?.string() ?: "", OtpSendResponse::class.java))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** OTP login step 2: verify code → tokens (persisted via SessionStore). */
    suspend fun otpVerify(sessionId: String, code: String): Result<AuthTokensResponse> = withContext(Dispatchers.IO) {
        val json = gson.toJson(mapOf("otp_session_id" to sessionId, "otp_code" to code, "device_uid" to "android-debug"))
        val request = Request.Builder()
            .url("$BASE_URL/auth/otp/verify")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(response.body?.string() ?: "OTP verify failed"))
                }
                Result.success(gson.fromJson(response.body?.string() ?: "", AuthTokensResponse::class.java))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Base for WS upgrades: http(s)://host/api/v1 → ws(s)://host. */
    fun wsBaseUrl(): String {
        val noApi = BASE_URL.substringBefore("/api/v1")
        return when {
            noApi.startsWith("https://") -> "wss://" + noApi.removePrefix("https://")
            else -> "ws://" + noApi.removePrefix("http://")
        }
    }

    /** Create a host room for the Go-Live flow. */
    suspend fun createRoom(title: String, categoryId: String = "party"): Result<CreateRoomResponse> = withContext(Dispatchers.IO) {
        val json = gson.toJson(mapOf("title" to title, "category_id" to categoryId))
        val request = Request.Builder()
            .url("$BASE_URL/rooms/create")
            .header("Authorization", SessionStore.authorization())
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(response.body?.string() ?: "Create room failed"))
                }
                Result.success(gson.fromJson(response.body?.string() ?: "", CreateRoomResponse::class.java))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Leave a live room (presence is dropped server-side). */
    suspend fun leaveRoom(roomId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/rooms/$roomId/leave")
            .header("Authorization", SessionStore.authorization())
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(response.body?.string() ?: "Leave room failed"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Join a live room as a viewer (bans and closed rooms are rejected). */
    suspend fun joinRoom(roomId: Long): Result<JoinRoomResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/rooms/$roomId/join")
            .header("Authorization", SessionStore.authorization())
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException(response.body?.string() ?: "Join room failed"))
                }
                Result.success(gson.fromJson(response.body?.string() ?: "", JoinRoomResponse::class.java))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLeaderboards(): Result<List<LeaderboardItem>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/leaderboards")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val type = object : TypeToken<List<LeaderboardItem>>() {}.type
                Result.success(gson.fromJson(response.body?.string() ?: "[]", type))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchDailyTasks(): Result<List<DailyTaskItem>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/tasks/daily")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val type = object : TypeToken<List<DailyTaskItem>>() {}.type
                Result.success(gson.fromJson(response.body?.string() ?: "[]", type))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun claimDailyTask(taskId: String): Result<Long> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/tasks/$taskId/claim")
            .header("Authorization", SessionStore.authorization())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val json = JSONObject(response.body?.string() ?: "{}")
                Result.success(json.optLong("reward_coins", 0L))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchVipTiers(): Result<List<VipTierItem>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/vip/tiers")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val type = object : TypeToken<List<VipTierItem>>() {}.type
                Result.success(gson.fromJson(response.body?.string() ?: "[]", type))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchKycStatus(): Result<com.unrealstar.live.models.KycStatus> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/kyc/status")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val status = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.KycStatus::class.java)
                Result.success(status)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMyFamily(): Result<com.unrealstar.live.models.MyFamilyResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/family/my")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.MyFamilyResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchFamilyLeaderboard(): Result<List<com.unrealstar.live.models.FamilyItem>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/family/leaderboard")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val type = object : TypeToken<List<com.unrealstar.live.models.FamilyItem>>() {}.type
                Result.success(gson.fromJson(response.body?.string() ?: "[]", type))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinFamily(familyId: String): Result<com.unrealstar.live.models.MyFamilyResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/family/join/$familyId")
            .header("Authorization", SessionStore.authorization())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.MyFamilyResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestPkMatch(roomId: Long): Result<String> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply { put("room_id", roomId) }
        val request = Request.Builder()
            .url("$BASE_URL/pk/match/request")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = JSONObject(response.body?.string() ?: "{}")
                Result.success(res.optString("match_ticket_id", "ticket_ok"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPkInvite(fromRoomId: Long, toHostId: Long, durationSecs: Int = 300): Result<String> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("from_room_id", fromRoomId)
            put("to_host_id", toHostId)
            put("duration_secs", durationSecs)
        }
        val request = Request.Builder()
            .url("$BASE_URL/pk/invite")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = JSONObject(response.body?.string() ?: "{}")
                Result.success(res.optString("invite_id", ""))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPkStatus(pkId: String): Result<com.unrealstar.live.models.PkStatusResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/pk/$pkId/status")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.PkStatusResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun respondPkInvite(inviteId: String, accept: Boolean): Result<com.unrealstar.live.models.PkStatusResponse> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("action", if (accept) "accept" else "decline")
        }
        val request = Request.Builder()
            .url("$BASE_URL/pk/invite/$inviteId/respond")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.PkStatusResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun followUser(userId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/social/follow/$userId")
            .header("Authorization", SessionStore.authorization())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = JSONObject(response.body?.string() ?: "{}")
                Result.success(res.optBoolean("is_following", true))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unfollowUser(userId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/social/unfollow/$userId")
            .header("Authorization", SessionStore.authorization())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = JSONObject(response.body?.string() ?: "{}")
                Result.success(res.optBoolean("is_following", false))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/payouts/preview (Trendo Earnings) */
    suspend fun fetchPayoutPreview(): Result<com.unrealstar.live.models.PayoutPreviewResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/payouts/preview")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.PayoutPreviewResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/search (Trendo SearchActivity) */
    suspend fun search(query: String, filter: String = "all"): Result<com.unrealstar.live.models.SearchResponse> = withContext(Dispatchers.IO) {
        val encodedQ = java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$BASE_URL/search?q=$encodedQ&filter=$filter")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.SearchResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/users/me/bag (Trendo MyBagActivity & StoreBagActivity) */
    suspend fun fetchMyBag(): Result<com.unrealstar.live.models.UserBagResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/users/me/bag")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.UserBagResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /api/v1/users/me/bag/equip */
    suspend fun equipCosmetic(itemId: String, category: String, equip: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("item_id", itemId)
            put("category", category)
            put("equip", equip)
        }
        val request = Request.Builder()
            .url("$BASE_URL/users/me/bag/equip")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = JSONObject(response.body?.string() ?: "{}")
                Result.success(res.optBoolean("success", true))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/users/me/level (Trendo MyLevelActivity) */
    suspend fun fetchUserLevel(): Result<com.unrealstar.live.models.UserLevelResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/users/me/level")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.UserLevelResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/users/me/badges (Trendo BadgeWallActivity) */
    suspend fun fetchUserBadges(): Result<com.unrealstar.live.models.UserBadgesResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/users/me/badges")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.UserBadgesResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /api/v1/payouts/request (Trendo Creator Cashout) */
    suspend fun requestPayout(diamondsAmount: Long, idempotencyKey: String = java.util.UUID.randomUUID().toString()): Result<com.unrealstar.live.models.PayoutRecord> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("diamonds_amount", diamondsAmount)
            put("idempotency_key", idempotencyKey)
        }
        val request = Request.Builder()
            .url("$BASE_URL/payouts/request")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.body?.string()}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.PayoutRecord::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/payouts/history */
    suspend fun fetchPayoutHistory(): Result<List<com.unrealstar.live.models.PayoutRecord>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/payouts/history")
            .header("Authorization", SessionStore.authorization())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val type = object : TypeToken<List<com.unrealstar.live.models.PayoutRecord>>() {}.type
                Result.success(gson.fromJson(response.body?.string() ?: "[]", type))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /api/v1/payments/packages (Trendo Coin Packages) */
    suspend fun fetchCoinPackages(): Result<List<com.unrealstar.live.models.CoinPackage>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/payments/packages")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                val type = object : TypeToken<List<com.unrealstar.live.models.CoinPackage>>() {}.type
                Result.success(gson.fromJson(response.body?.string() ?: "[]", type))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /api/v1/payments/orders/create */
    suspend fun createWalletOrder(packageId: String, provider: String = "test-provider"): Result<com.unrealstar.live.models.CreateOrderResponse> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("package_id", packageId)
            put("provider", provider)
            put("idempotency_key", java.util.UUID.randomUUID().toString())
        }
        val request = Request.Builder()
            .url("$BASE_URL/payments/orders/create")
            .header("Authorization", SessionStore.authorization())
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.body?.string()}"))
                val res = gson.fromJson(response.body?.string() ?: "{}", com.unrealstar.live.models.CreateOrderResponse::class.java)
                Result.success(res)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /api/v1/payments/webhook/{provider} (Simulate instant top-up) */
    suspend fun simulatePaymentSuccess(orderId: String, provider: String = "test-provider"): Result<Boolean> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("order_id", orderId)
            put("status", "success")
        }
        val request = Request.Builder()
            .url("$BASE_URL/payments/webhook/$provider")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.body?.string()}"))
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}



