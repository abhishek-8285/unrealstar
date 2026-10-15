package com.unrealstar.live

import com.google.gson.Gson
import com.unrealstar.live.models.DailyTaskItem
import com.unrealstar.live.models.FeedPage
import com.unrealstar.live.models.FeedPost
import com.unrealstar.live.models.LeaderboardItem
import com.unrealstar.live.models.MomentEngagement
import com.unrealstar.live.models.SendGiftRequest
import com.unrealstar.live.models.VipTierItem
import com.unrealstar.live.models.WalletBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsSerializationTest {

    private val gson = Gson()

    @Test
    fun testLeaderboardItemDeserialization() {
        val json = """
            {
                "rank": 1,
                "user_id": 1001,
                "nickname": "Aarav",
                "avatar_url": "/avatars/aarav.jpg",
                "score": 52000
            }
        """.trimIndent()

        val item = gson.fromJson(json, LeaderboardItem::class.java)
        assertEquals(1L, item.rank)
        assertEquals(1001L, item.user_id)
        assertEquals("Aarav", item.nickname)
        assertEquals("/avatars/aarav.jpg", item.avatar_url)
        assertEquals(52000L, item.score)
    }

    @Test
    fun testDailyTaskItemDeserialization() {
        val json = """
            {
                "task_id": "task_checkin",
                "title": "Daily Check-in",
                "progress": 1,
                "target": 1,
                "reward_coins": 10,
                "is_claimed": false
            }
        """.trimIndent()

        val task = gson.fromJson(json, DailyTaskItem::class.java)
        assertEquals("task_checkin", task.task_id)
        assertEquals("Daily Check-in", task.title)
        assertEquals(1, task.progress)
        assertEquals(1, task.target)
        assertEquals(10L, task.reward_coins)
        assertFalse(task.is_claimed)
    }

    @Test
    fun testVipTierItemDeserialization() {
        val json = """
            {
                "tier_id": "vip_bronze",
                "name": "Bronze VIP",
                "price_inr": 99,
                "privileges": [
                    "Bronze Badge",
                    "Exclusive Entrance Animation"
                ]
            }
        """.trimIndent()

        val tier = gson.fromJson(json, VipTierItem::class.java)
        assertEquals("vip_bronze", tier.tier_id)
        assertEquals("Bronze VIP", tier.name)
        assertEquals(99, tier.price_inr)
        assertEquals(2, tier.privileges.size)
        assertEquals("Bronze Badge", tier.privileges[0])
    }

    @Test
    fun testSendGiftRequestSerialization() {
        val req = SendGiftRequest(
            room_id = 42L,
            recipient_id = 9999L,
            gift_id = "gift_sparkler_1",
            count = 5,
            combo_seq = 1,
            idempotency_key = "idemp_test_123"
        )

        val json = gson.toJson(req)
        val deserialized = gson.fromJson(json, SendGiftRequest::class.java)

        assertEquals("gift_sparkler_1", deserialized.gift_id)
        assertEquals(9999L, deserialized.recipient_id)
        assertEquals(42L, deserialized.room_id)
        assertEquals(5, deserialized.count)
        assertEquals(1, deserialized.combo_seq)
        assertEquals("idemp_test_123", deserialized.idempotency_key)
    }

    @Test
    fun testFeedPostAndPageDeserialization() {
        val json = """
            {
                "posts": [
                    {
                        "moment_id": "moment_101",
                        "author_id": 505,
                        "author_nickname": "Ananya",
                        "media_url": "https://example.com/video.mp4",
                        "caption": "Live performance sneak peek!",
                        "likes_count": 120,
                        "liked_by_me": true,
                        "tags": ["live", "dance"]
                    }
                ],
                "next_cursor": "cursor_page_2"
            }
        """.trimIndent()

        val page = gson.fromJson(json, FeedPage::class.java)
        assertNotNull(page)
        assertEquals("cursor_page_2", page.next_cursor)
        assertEquals(1, page.posts.size)

        val post = page.posts[0]
        assertEquals("moment_101", post.moment_id)
        assertEquals(505L, post.author_id)
        assertEquals("Ananya", post.author_nickname)
        assertEquals("Live performance sneak peek!", post.caption)
        assertEquals(120L, post.likes_count)
        assertTrue(post.liked_by_me)
        assertEquals(listOf("live", "dance"), post.tags)
    }

    @Test
    fun testMomentEngagementAndWallet() {
        val engagementJson = """
            {
                "moment_id": "m1",
                "likes_count": 42,
                "comment_count": 7,
                "share_count": 3,
                "gift_count": 10
            }
        """.trimIndent()

        val engagement = gson.fromJson(engagementJson, MomentEngagement::class.java)
        assertEquals(42L, engagement.likes_count)
        assertEquals(7L, engagement.comment_count)
        assertEquals(3L, engagement.share_count)
        assertEquals(10L, engagement.gift_count)

        val walletJson = """{"coins": 500, "diamonds": 120}"""
        val wallet = gson.fromJson(walletJson, WalletBalance::class.java)
        assertEquals(500L, wallet.coins)
        assertEquals(120L, wallet.diamonds)
    }
}
