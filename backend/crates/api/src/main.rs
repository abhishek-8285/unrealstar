use camstar_api::models::MomentItem;
use camstar_api::{create_router, AppState};
use chrono::Utc;

/// Origin the phone fetches from. Overridable so a USB/loopback setup can
/// point the app at this host without publishing a DNS name.
fn origin() -> String {
    std::env::var("PUBLIC_ORIGIN").unwrap_or_else(|_| "https://us.avandab.com".to_string())
}
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Ledger persistence: when DATABASE_URL is set, build AppState against
    // a Postgres-backed ledger (migrations run inside LedgerBackend::postgres).
    // Fall back to in-memory on any connection/migration error so a transient
    // DB blip does not prevent the server from booting.
    let state = if let Ok(url) = std::env::var("DATABASE_URL") {
        match AppState::with_postgres(&url).await {
            Ok(state) => {
                let entries = state.ledger.entries().await;
                println!("🗄️ Postgres ledger ready: {} entries", entries.len());
                state
            }
            Err(e) => {
                eprintln!(
                    "⚠️ DATABASE_URL set but PostgresLedger failed: {e}; continuing in-memory"
                );
                AppState::new()
            }
        }
    } else {
        println!("💾 In-memory mode (set DATABASE_URL for Postgres ledger)");
        AppState::new()
    };

    // Create demo users with coins for testing
    let demo_user_id = state.create_user("+919876543210", 1000).await;
    let _ = state.create_user("+919999999999", 5000).await;
    // Also explicitly give user 1001 and host 9999 accounts in ledger and seed Room 1
    {
        use camstar_api::state::{
            system_clearing_account, user_coins_account, user_diamonds_account, RoomSession,
        };
        use camstar_ledger::{AccountKind, Direction, EntryKind, Line, Money};
        use camstar_roles::RoomRoster;

        let _ = state
            .ledger
            .register_account(user_coins_account(1001), AccountKind::Owned)
            .await;
        let _ = state
            .ledger
            .register_account(user_diamonds_account(1001), AccountKind::Owned)
            .await;
        let amount = Money::from_minor(5000).unwrap();
        let _ = state
            .ledger
            .post(
                EntryKind::TopUp,
                "seed_1001",
                vec![
                    Line {
                        account: system_clearing_account(),
                        direction: Direction::Debit,
                        amount,
                    },
                    Line {
                        account: user_coins_account(1001),
                        direction: Direction::Credit,
                        amount,
                    },
                ],
            )
            .await;

        // Host 9999
        let _ = state
            .ledger
            .register_account(user_coins_account(9999), AccountKind::Owned)
            .await;
        let _ = state
            .ledger
            .register_account(user_diamonds_account(9999), AccountKind::Owned)
            .await;

        // Seed a realistic set of live rooms. The Live grid is a two-column
        // card wall, so the fixtures deliberately span every tab (party,
        // talent, pk, nearby) and a wide viewer range, plus the PK pairs and
        // promotional chips the card chrome renders.
        struct SeedRoom {
            id: u64,
            title: &'static str,
            host: u64,
            category: &'static str,
            viewers: u64,
            rank_tag: Option<&'static str>,
            host_badge: Option<&'static str>,
            is_pk: bool,
            pk_opponent: Option<&'static str>,
        }

        let seed_rooms = [
            SeedRoom {
                id: 1,
                title: "Be My Guest Tonight",
                host: 9999,
                category: "party",
                viewers: 1204,
                rank_tag: Some("Hourly TOP!"),
                host_badge: Some("👑"),
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 2,
                title: "Bollywood Nights",
                host: 2101,
                category: "party",
                viewers: 382,
                rank_tag: None,
                host_badge: Some("🎀"),
                is_pk: true,
                pk_opponent: Some("trisha"),
            },
            SeedRoom {
                id: 3,
                title: "Unplugged & Chill",
                host: 2102,
                category: "talent",
                viewers: 278,
                rank_tag: Some("Hourly TOP!"),
                host_badge: None,
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 4,
                title: "Coffee Date Live",
                host: 2103,
                category: "nearby",
                viewers: 206,
                rank_tag: None,
                host_badge: Some("💖"),
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 5,
                title: "Dimple's Room",
                host: 2104,
                category: "party",
                viewers: 217,
                rank_tag: Some("Free coins"),
                host_badge: Some("🎁"),
                is_pk: true,
                pk_opponent: Some("aisha"),
            },
            SeedRoom {
                id: 6,
                title: "Late Night Talk",
                host: 2105,
                category: "talent",
                viewers: 98,
                rank_tag: None,
                host_badge: None,
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 7,
                title: "Game Night Arena",
                host: 2106,
                category: "party",
                viewers: 1420,
                rank_tag: Some("Hot 🔥"),
                host_badge: Some("👑"),
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 8,
                title: "Sing Along With Me",
                host: 2107,
                category: "talent",
                viewers: 511,
                rank_tag: None,
                host_badge: Some("🎤"),
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 9,
                title: "Dance Floor Open",
                host: 2108,
                category: "party",
                viewers: 640,
                rank_tag: None,
                host_badge: Some("💃"),
                is_pk: true,
                pk_opponent: Some("meera"),
            },
            SeedRoom {
                id: 10,
                title: "Study Buddy Lounge",
                host: 2109,
                category: "nearby",
                viewers: 74,
                rank_tag: None,
                host_badge: None,
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 11,
                title: "New Star Debut",
                host: 2110,
                category: "talent",
                viewers: 45,
                rank_tag: Some("New star"),
                host_badge: Some("✨"),
                is_pk: false,
                pk_opponent: None,
            },
            SeedRoom {
                id: 12,
                title: "Weekend Party Pit",
                host: 2111,
                category: "party",
                viewers: 1733,
                rank_tag: Some("Hourly TOP!"),
                host_badge: Some("👑"),
                is_pk: false,
                pk_opponent: None,
            },
        ];

        let mut rooms = state.rooms.write().await;
        for seed in seed_rooms {
            let mut roster = RoomRoster::new(seed.id, seed.host);
            roster.set_viewer_count(seed.viewers);
            rooms.insert(
                seed.id,
                RoomSession {
                    room_id: seed.id,
                    title: seed.title.into(),
                    host_id: seed.host,
                    category: seed.category.into(),
                    cover_image_url: format!("{}/assets/covers/{}.jpg", origin(), seed.id),
                    roster,
                    is_active: true,
                    peak_viewers: seed.viewers,
                    diamonds_earned: 0,
                    is_pk: seed.is_pk,
                    pk_opponent: seed.pk_opponent.map(str::to_string),
                    rank_tag: seed.rank_tag.map(str::to_string),
                    host_badge: seed.host_badge.map(str::to_string),
                },
            );
        }
        // Seed a feed of moments. Without these the Home tab renders an empty
        // wall: moments were only ever created through the API, so every
        // backend restart emptied the feed. Clips are served by the local
        // media host on :8000 (adb reverse) for device testing.
        // The media server's document root IS the media dir, so no /media prefix.
        let media = |path: &str| format!("{}/assets/{path}", origin());
        let clip = |n: u32| media(&format!("m{n}.mp4"));
        let poster = |n: u32| media(&format!("posters/p{n}.jpg"));
        let tagify = |caption: &str| -> Vec<String> {
            caption
                .split_whitespace()
                .filter_map(|w| w.strip_prefix('#'))
                .map(|t| {
                    t.trim_matches(|c: char| !c.is_alphanumeric())
                        .to_lowercase()
                })
                .filter(|t| !t.is_empty())
                .collect()
        };

        struct SeedMoment {
            id: &'static str,
            author: &'static str,
            media: String,
            poster: String,
            caption: &'static str,
            likes: u64,
            gifts: u64,
            comments: u64,
            shares: u64,
            promo: Option<&'static str>,
        }

        let seed_moments = [
            SeedMoment {
                id: "seed-1",
                author: "User_1001",
                media: clip(1),
                poster: poster(1),
                caption: "Sunset session #Live #Music",
                likes: 1243,
                gifts: 87,
                comments: 96,
                shares: 31,
                promo: None,
            },
            SeedMoment {
                id: "seed-2",
                author: "Host_2104",
                media: clip(2),
                poster: poster(2),
                caption: "Street dance practice #Dance #Live",
                likes: 892,
                gifts: 42,
                comments: 54,
                shares: 18,
                promo: Some("Trending tonight"),
            },
            SeedMoment {
                id: "seed-3",
                author: "Host_2107",
                media: clip(3),
                poster: poster(3),
                caption: "New song cover #Music #SingAlong",
                likes: 3405,
                gifts: 210,
                comments: 188,
                shares: 76,
                promo: None,
            },
            SeedMoment {
                id: "seed-4",
                author: "Host_2109",
                media: clip(1),
                poster: poster(4),
                caption: "Coffee and a quiet stream #Chill",
                likes: 517,
                gifts: 19,
                comments: 41,
                shares: 9,
                promo: None,
            },
            SeedMoment {
                id: "seed-5",
                author: "Host_2111",
                media: clip(2),
                poster: poster(5),
                caption: "Weekend party warmup #Party #Live",
                likes: 2760,
                gifts: 154,
                comments: 133,
                shares: 44,
                promo: Some("Join the party"),
            },
        ];

        let mut moments = state.moments.write().await;
        for (index, seed) in seed_moments.iter().enumerate() {
            moments.push(MomentItem {
                moment_id: seed.id.to_string(),
                author_id: 1001,
                author_nickname: seed.author.to_string(),
                media_url: seed.media.clone(),
                caption: seed.caption.to_string(),
                duration_secs: 12,
                likes_count: seed.likes,
                // Stagger created_at so "For you" ordering is stable.
                created_at: Utc::now() - chrono::Duration::seconds((index as i64) * 600),
                gift_count: seed.gifts,
                comment_count: seed.comments,
                share_count: seed.shares,
                avatar_url: media(&format!("avatars/a{}.jpg", index + 1)),
                cover_url: seed.poster.clone(),
                promo_text: seed.promo.map(str::to_string),
                tags: tagify(seed.caption),
            });
        }
    }
    // The Android debug build authenticates as user 1001, which was never in
    // the users map, so /users/me 404'd. Give it a real profile.
    {
        use camstar_api::models::UserProfile;
        use camstar_roles::{CreatorStatus, Tier, VipLevel};

        let mut users = state.users.write().await;
        users.insert(
            1001,
            UserProfile {
                user_id: 1001,
                phone: "+919000001001".into(),
                nickname: "PRISHA RAJPUT".into(),
                avatar_url: String::new(),
                bio: "ache logo ke sath bura bhi unke ache ke liye hota h".into(),
                gender: "female".into(),
                city: "Delhi".into(),
                age: 19,
                can_publish: true,
                creator_status: CreatorStatus::Viewer,
                wealth_tier: Tier::Newcomer,
                vip_level: VipLevel::None,
                followers_count: 19_662,
                following_count: 8,
                is_live: false,
                live_room_id: None,
                likes_received: 99_272,
            },
        );
    }
    println!("🚀 CamStar API Server initialized.");
    println!(
        "👤 Seeded demo user ID: {demo_user_id} with 1,000 coins and user 1001 with 5,000 coins"
    );
    println!("🎙️ Seeded {} live rooms across party/talent/pk/nearby", 12);
    println!("🎬 Seeded {} feed moments and the app user profile", 5);

    let app = create_router(state);
    let media_root = camstar_api::media_root();
    let port = std::env::var("PORT").unwrap_or_else(|_| "3000".to_string());
    // Default to loopback: in production Caddy terminates TLS and proxies in,
    // so the API has no reason to be reachable directly from the internet.
    // Set HOST=0.0.0.0 for adb-reverse device testing.
    let host = std::env::var("HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
    let addr = format!("{host}:{port}");
    let listener = TcpListener::bind(&addr).await?;
    println!("📡 Listening on http://{addr}");

    // nest_service strips the /assets prefix before hitting the directory, so
    // /assets/covers/1.jpg resolves to <MEDIA_ROOT>/covers/1.jpg. ServeDir
    // normalises and rejects traversal itself.
    let app = app.nest_service(
        "/assets",
        tower_http::services::ServeDir::new(media_root).append_index_html_on_directories(false),
    );

    axum::serve(listener, app).await?;
    Ok(())
}
