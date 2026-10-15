use camstar_ledger::Money;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Tier {
    Newcomer,
    Established,
    Notable,
    Star,
    Superstar,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum VipLevel {
    None,
    One,
    Two,
    Three,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusProfile {
    pub tier: Tier,
    pub vip: VipLevel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StatusInputs {
    pub lifetime_received: Money,
    pub consecutive_days: u32,
    pub lifetime_topped_up: Money,
}

impl StatusInputs {
    pub fn new(lifetime_received: Money, consecutive_days: u32, lifetime_topped_up: Money) -> Self {
        StatusInputs {
            lifetime_received,
            consecutive_days,
            lifetime_topped_up,
        }
    }
}

pub fn derive_tier(inputs: StatusInputs) -> Tier {
    let received = inputs.lifetime_received.to_minor();
    match received {
        m if m < 50_000 => Tier::Newcomer,
        m if m < 500_000 => Tier::Established,
        m if m < 5_000_000 => Tier::Notable,
        m if m < 50_000_000 => Tier::Star,
        _ => Tier::Superstar,
    }
}

pub fn derive_vip(inputs: StatusInputs) -> VipLevel {
    let topped_up = inputs.lifetime_topped_up.to_minor();
    if topped_up >= 1_000_000 {
        VipLevel::Three
    } else if topped_up >= 200_000 {
        VipLevel::Two
    } else if topped_up >= 50_000 {
        VipLevel::One
    } else {
        VipLevel::None
    }
}

pub fn derive(inputs: StatusInputs) -> StatusProfile {
    StatusProfile {
        tier: derive_tier(inputs),
        vip: derive_vip(inputs),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CreatorStatus {
    Viewer,
    Streamer,
    Established,
    Featured,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CreatorInputs {
    pub streams_published: u32,
    pub peak_viewers: u32,
    pub lifetime_received: Money,
}

impl CreatorInputs {
    pub fn new(streams_published: u32, peak_viewers: u32, lifetime_received: Money) -> Self {
        CreatorInputs {
            streams_published,
            peak_viewers,
            lifetime_received,
        }
    }

    pub fn never_published() -> Self {
        CreatorInputs::new(0, 0, Money::zero())
    }
}

pub fn derive_creator_status(inputs: CreatorInputs) -> CreatorStatus {
    if inputs.streams_published == 0 {
        return CreatorStatus::Viewer;
    }
    let received = inputs.lifetime_received.to_minor();
    if inputs.streams_published >= 50 && inputs.peak_viewers >= 5_000 && received >= 1_000_000 {
        return CreatorStatus::Featured;
    }
    if inputs.streams_published >= 10 && inputs.peak_viewers >= 500 {
        return CreatorStatus::Established;
    }
    CreatorStatus::Streamer
}

#[cfg(test)]
mod tests {
    use super::*;

    fn money(minor: i64) -> Money {
        Money::from_minor(minor).expect("valid")
    }

    #[test]
    fn lifetime_receipt_ladders_the_tier() {
        assert_eq!(
            derive_tier(StatusInputs::new(money(0), 0, money(0))),
            Tier::Newcomer
        );
        assert_eq!(
            derive_tier(StatusInputs::new(money(49_999), 0, money(0))),
            Tier::Newcomer
        );
        assert_eq!(
            derive_tier(StatusInputs::new(money(50_000), 0, money(0))),
            Tier::Established
        );
        assert_eq!(
            derive_tier(StatusInputs::new(money(500_000), 0, money(0))),
            Tier::Notable
        );
        assert_eq!(
            derive_tier(StatusInputs::new(money(5_000_000), 0, money(0))),
            Tier::Star
        );
        assert_eq!(
            derive_tier(StatusInputs::new(money(50_000_000), 0, money(0))),
            Tier::Superstar
        );
    }

    #[test]
    fn a_newcomer_who_lifetime_receives_nothing_is_not_a_star() {
        let profile = derive(StatusInputs::new(money(0), 365, money(0)));
        assert_eq!(profile.tier, Tier::Newcomer);
        assert_eq!(profile.vip, VipLevel::None);
    }

    #[test]
    fn spending_does_not_raise_the_tier() {
        let spender = derive(StatusInputs::new(money(0), 30, money(2_000_000)));
        assert_eq!(spender.tier, Tier::Newcomer);
        assert_eq!(spender.vip, VipLevel::Three);
    }

    #[test]
    fn receiving_raises_the_tier_without_any_top_up() {
        let creator = derive(StatusInputs::new(money(5_000_000), 0, money(0)));
        assert_eq!(creator.tier, Tier::Star);
        assert_eq!(creator.vip, VipLevel::None);
    }

    #[test]
    fn streak_days_alone_do_not_promote_anyone() {
        let base = StatusInputs::new(money(0), 0, money(0));
        let streak = StatusInputs::new(money(0), 9_999, money(0));
        assert_eq!(derive_tier(base), derive_tier(streak));
        assert_eq!(derive_vip(base), derive_vip(streak));
    }

    #[test]
    fn vip_ladders_with_lifetime_top_up_only() {
        assert_eq!(
            derive_vip(StatusInputs::new(money(0), 0, money(49_999))),
            VipLevel::None
        );
        assert_eq!(
            derive_vip(StatusInputs::new(money(0), 0, money(50_000))),
            VipLevel::One
        );
        assert_eq!(
            derive_vip(StatusInputs::new(money(0), 0, money(200_000))),
            VipLevel::Two
        );
        assert_eq!(
            derive_vip(StatusInputs::new(money(0), 0, money(1_000_000))),
            VipLevel::Three
        );
    }

    #[test]
    fn someone_who_has_never_published_is_simply_a_viewer() {
        assert_eq!(
            derive_creator_status(CreatorInputs::never_published()),
            CreatorStatus::Viewer
        );
    }

    #[test]
    fn the_first_stream_makes_someone_a_streamer() {
        let first = CreatorInputs::new(1, 0, money(0));
        assert_eq!(derive_creator_status(first), CreatorStatus::Streamer);
    }

    #[test]
    fn a_single_gifted_stream_does_not_make_someone_featured() {
        let lucky = CreatorInputs::new(1, 0, money(50_000_000));
        assert_eq!(derive_creator_status(lucky), CreatorStatus::Streamer);
    }

    #[test]
    fn status_needs_repeat_publishing_and_a_real_audience() {
        assert_eq!(
            derive_creator_status(CreatorInputs::new(50, 100, money(1_000_000))),
            CreatorStatus::Streamer
        );
        assert_eq!(
            derive_creator_status(CreatorInputs::new(9, 50_000, money(9_000_000))),
            CreatorStatus::Streamer
        );
        assert_eq!(
            derive_creator_status(CreatorInputs::new(12, 600, money(100_000))),
            CreatorStatus::Established
        );
        assert_eq!(
            derive_creator_status(CreatorInputs::new(200, 20_000, money(5_000_000))),
            CreatorStatus::Featured
        );
    }

    #[test]
    fn creator_status_and_wealth_tier_are_independent_axes() {
        let generous_viewer = CreatorInputs::new(0, 0, money(50_000_000));
        assert_eq!(
            derive_creator_status(generous_viewer),
            CreatorStatus::Viewer
        );
        assert_eq!(
            derive_tier(StatusInputs::new(money(50_000_000), 0, money(0))),
            Tier::Superstar
        );
    }
}
