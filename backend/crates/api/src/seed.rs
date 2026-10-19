//! Demo fixtures for the Live grid. Extracted from `main` so tests can seed
//! the exact rooms the app boots with.

use std::collections::HashSet;

use camstar_roles::RoomRoster;

use crate::state::{AppState, RoomSession};

/// Origin the phone fetches from. Overridable so a USB/loopback setup can
/// point the app at this host without publishing a DNS name.
pub fn origin() -> String {
    std::env::var("PUBLIC_ORIGIN").unwrap_or_else(|_| "https://us.avandab.com".to_string())
}

/// Seed a realistic set of live rooms. The Live grid is a two-column card
/// wall, so the fixtures deliberately span every tab (party, talent, pk,
/// nearby) and a wide viewer range, plus the PK pairs and promotional chips
/// the card chrome renders.
pub async fn seed_demo_rooms(state: &AppState) {
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
                present: HashSet::new(),
                viewers_base: seed.viewers,
                started_at: chrono::Utc::now(),
            },
        );
    }
}
