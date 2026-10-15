use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum AccountType {
    User,
    Official,
    Agency,
    System,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Capability {
    OccupyGuestSeat,
    ModerateRoom,
    MuteAudience,
    ManageRoomSettings,
    SendGift,
    ReceiveGift,
    ViewCreatorAnalytics,
    ManageSubAccounts,
    RequestPayout,
    ApprovePayout,
    ContactSupport,
}

impl Capability {
    pub fn is_sensitive(self) -> bool {
        matches!(
            self,
            Capability::RequestPayout
                | Capability::ApprovePayout
                | Capability::ManageSubAccounts
                | Capability::ManageRoomSettings
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AccountState {
    pub age_confirmed: bool,
    pub phone_verified: bool,
    pub banned: bool,
}

impl AccountState {
    pub fn new(age_confirmed: bool, phone_verified: bool, banned: bool) -> Self {
        AccountState {
            age_confirmed,
            phone_verified,
            banned,
        }
    }

    pub fn verified() -> Self {
        AccountState::new(true, true, false)
    }

    pub fn can_publish(&self) -> bool {
        self.age_confirmed && self.phone_verified && !self.banned
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Scope {
    Platform,
    Room(u64),
    Account(u64),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Grant {
    pub expires_at: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Denial {
    NoGrant,
    Expired,
    WrongAccountType,
}

#[derive(Debug, Default)]
pub struct Authorizer {
    grants: HashMap<(u64, Capability, Scope), Grant>,
}

impl Authorizer {
    pub fn new() -> Self {
        Authorizer::default()
    }

    pub fn grant(
        &mut self,
        account: u64,
        account_type: AccountType,
        capability: Capability,
        scope: Scope,
        expires_at: Option<u64>,
    ) {
        if capability.is_sensitive() && account_type == AccountType::User && expires_at.is_none() {
            return;
        }
        self.grants
            .insert((account, capability, scope), Grant { expires_at });
    }

    pub fn revoke(&mut self, account: u64, capability: Capability, scope: Scope) -> bool {
        self.grants.remove(&(account, capability, scope)).is_some()
    }

    pub fn can(
        &self,
        account: u64,
        account_type: AccountType,
        capability: Capability,
        scope: Scope,
        now: u64,
    ) -> Result<(), Denial> {
        if account_type == AccountType::System {
            return Ok(());
        }
        if capability.is_sensitive() && account_type == AccountType::User {
            return Err(Denial::WrongAccountType);
        }
        let grant = self
            .grants
            .get(&(account, capability, scope))
            .or_else(|| self.grants.get(&(account, capability, Scope::Platform)))
            .ok_or(Denial::NoGrant)?;
        if let Some(expiry) = grant.expires_at {
            if now >= expiry {
                return Err(Denial::Expired);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ROOM: u64 = 42;

    fn authorizer_with_grant() -> (Authorizer, u64) {
        let mut auth = Authorizer::new();
        auth.grant(
            7,
            AccountType::User,
            Capability::ModerateRoom,
            Scope::Room(ROOM),
            None,
        );
        (auth, 7)
    }

    #[test]
    fn a_granted_capability_in_scope_is_allowed() {
        let (auth, account) = authorizer_with_grant();
        assert_eq!(
            auth.can(
                account,
                AccountType::User,
                Capability::ModerateRoom,
                Scope::Room(ROOM),
                1000
            ),
            Ok(())
        );
    }

    #[test]
    fn a_grant_does_not_leak_into_another_room() {
        let (auth, account) = authorizer_with_grant();
        assert_eq!(
            auth.can(
                account,
                AccountType::User,
                Capability::ModerateRoom,
                Scope::Room(999),
                1000
            ),
            Err(Denial::NoGrant)
        );
    }

    #[test]
    fn an_expired_grant_stops_working_at_its_expiry() {
        let mut auth = Authorizer::new();
        auth.grant(
            7,
            AccountType::User,
            Capability::ModerateRoom,
            Scope::Room(ROOM),
            Some(5000),
        );
        assert_eq!(
            auth.can(
                7,
                AccountType::User,
                Capability::ModerateRoom,
                Scope::Room(ROOM),
                4999
            ),
            Ok(())
        );
        assert_eq!(
            auth.can(
                7,
                AccountType::User,
                Capability::ModerateRoom,
                Scope::Room(ROOM),
                5000
            ),
            Err(Denial::Expired)
        );
    }

    #[test]
    fn moderation_grants_survive_revocation_but_only_for_that_room() {
        let (mut auth, account) = authorizer_with_grant();
        assert!(auth.revoke(account, Capability::ModerateRoom, Scope::Room(ROOM)));
        assert_eq!(
            auth.can(
                account,
                AccountType::User,
                Capability::ModerateRoom,
                Scope::Room(ROOM),
                1000
            ),
            Err(Denial::NoGrant)
        );
    }

    #[test]
    fn an_ordinary_user_can_never_hold_a_payout_capability() {
        let mut auth = Authorizer::new();
        auth.grant(
            7,
            AccountType::User,
            Capability::RequestPayout,
            Scope::Platform,
            None,
        );
        assert_eq!(
            auth.can(
                7,
                AccountType::User,
                Capability::RequestPayout,
                Scope::Platform,
                1000
            ),
            Err(Denial::WrongAccountType)
        );
    }

    #[test]
    fn an_agency_account_can_hold_payout_approval() {
        let mut auth = Authorizer::new();
        auth.grant(
            9,
            AccountType::Agency,
            Capability::ApprovePayout,
            Scope::Account(7),
            None,
        );
        assert_eq!(
            auth.can(
                9,
                AccountType::Agency,
                Capability::ApprovePayout,
                Scope::Account(7),
                1000
            ),
            Ok(())
        );
        assert_eq!(
            auth.can(
                9,
                AccountType::Agency,
                Capability::ApprovePayout,
                Scope::Account(8),
                1000
            ),
            Err(Denial::NoGrant)
        );
    }

    #[test]
    fn a_platform_scope_grant_covers_any_scope() {
        let mut auth = Authorizer::new();
        auth.grant(
            3,
            AccountType::Official,
            Capability::SendGift,
            Scope::Platform,
            None,
        );
        assert_eq!(
            auth.can(
                3,
                AccountType::Official,
                Capability::SendGift,
                Scope::Room(1),
                0
            ),
            Ok(())
        );
        assert_eq!(
            auth.can(
                3,
                AccountType::Official,
                Capability::SendGift,
                Scope::Room(2),
                0
            ),
            Ok(())
        );
    }

    #[test]
    fn the_system_account_type_is_unconditional() {
        let auth = Authorizer::new();
        assert_eq!(
            auth.can(
                0,
                AccountType::System,
                Capability::ApprovePayout,
                Scope::Account(1234),
                10_000
            ),
            Ok(())
        );
    }

    #[test]
    fn an_expired_agency_grant_reports_expiry_not_account_type() {
        let mut auth = Authorizer::new();
        auth.grant(
            9,
            AccountType::Agency,
            Capability::ApprovePayout,
            Scope::Account(7),
            Some(100),
        );
        assert_eq!(
            auth.can(
                9,
                AccountType::Agency,
                Capability::ApprovePayout,
                Scope::Account(7),
                99
            ),
            Ok(())
        );
        assert_eq!(
            auth.can(
                9,
                AccountType::Agency,
                Capability::ApprovePayout,
                Scope::Account(7),
                101
            ),
            Err(Denial::Expired)
        );
    }

    #[test]
    fn a_user_reaching_for_a_payout_capability_is_refused_before_any_grant_is_consulted() {
        let mut auth = Authorizer::new();
        auth.grant(
            9,
            AccountType::User,
            Capability::RequestPayout,
            Scope::Platform,
            Some(9_999),
        );
        assert_eq!(
            auth.can(
                9,
                AccountType::User,
                Capability::RequestPayout,
                Scope::Platform,
                1_000_000
            ),
            Err(Denial::WrongAccountType)
        );
    }
}
