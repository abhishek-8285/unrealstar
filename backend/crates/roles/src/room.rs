use std::collections::BTreeMap;

pub const MAX_SEATS: usize = 15;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SeatRole {
    CoHost,
    Guest,
    Moderator,
}

impl SeatRole {
    pub fn may_gift(self) -> bool {
        matches!(self, SeatRole::CoHost | SeatRole::Guest)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Seat {
    pub slot: u8,
    pub occupant: u64,
    pub role: SeatRole,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Denial {
    SeatFull,
    HostNotSeated,
    HostCannotGiftToSelf,
    NotSeated,
    AlreadySeated,
    SeatOccupied,
    SlotOutOfRange,
}

#[derive(Debug, Clone)]
pub struct RoomRoster {
    pub room: u64,
    pub host: u64,
    seats: BTreeMap<u8, Seat>,
    viewer_count: u64,
}

impl RoomRoster {
    pub fn new(room: u64, host: u64) -> Self {
        RoomRoster {
            room,
            host,
            seats: BTreeMap::new(),
            viewer_count: 0,
        }
    }

    pub fn seat(&self, slot: u8) -> Option<&Seat> {
        self.seats.get(&slot)
    }

    pub fn seat_of(&self, account: u64) -> Option<&Seat> {
        self.seats.values().find(|seat| seat.occupant == account)
    }

    pub fn viewer_count(&self) -> u64 {
        self.viewer_count
    }

    pub fn set_viewer_count(&mut self, count: u64) {
        self.viewer_count = count;
    }

    pub fn is_host(&self, account: u64) -> bool {
        self.host == account
    }

    pub fn take_seat(&mut self, account: u64, slot: u8, role: SeatRole) -> Result<&Seat, Denial> {
        if slot as usize >= MAX_SEATS {
            return Err(Denial::SlotOutOfRange);
        }
        if self.seat_of(account).is_some() {
            return Err(Denial::AlreadySeated);
        }
        if self.seats.contains_key(&slot) {
            return Err(Denial::SeatOccupied);
        }
        if self.seats.len() >= MAX_SEATS {
            return Err(Denial::SeatFull);
        }
        let seat = Seat {
            slot,
            occupant: account,
            role,
        };
        self.seats.insert(slot, seat);
        Ok(self.seats.get(&slot).expect("just inserted"))
    }

    pub fn leave(&mut self, account: u64) -> Result<Seat, Denial> {
        let seat = *self.seat_of(account).ok_or(Denial::NotSeated)?;
        self.seats.remove(&seat.slot);
        Ok(seat)
    }

    pub fn set_role(&mut self, account: u64, role: SeatRole) -> Result<Seat, Denial> {
        let slot = self.seat_of(account).ok_or(Denial::NotSeated)?.slot;
        let seat = self.seats.get_mut(&slot).expect("slot exists");
        seat.role = role;
        Ok(*seat)
    }

    pub fn can_send_gift(&self, sender: u64) -> Result<(), Denial> {
        if sender == self.host {
            return Err(Denial::HostCannotGiftToSelf);
        }
        if let Some(seat) = self.seat_of(sender) {
            if !seat.role.may_gift() {
                return Err(Denial::NotSeated);
            }
        }
        Ok(())
    }

    pub fn can_receive_gift(&self, receiver: u64) -> Result<(), Denial> {
        if receiver == self.host {
            return Ok(());
        }
        let seat = self.seat_of(receiver).ok_or(Denial::NotSeated)?;
        if !seat.role.may_gift() {
            return Err(Denial::NotSeated);
        }
        Ok(())
    }

    pub fn seat_count(&self) -> usize {
        self.seats.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::roles::AccountState;

    fn roster() -> RoomRoster {
        RoomRoster::new(1, 100)
    }

    #[test]
    fn the_host_is_not_one_of_the_seats() {
        let roster = roster();
        assert!(roster.is_host(100));
        assert_eq!(roster.seat_of(100), None);
    }

    #[test]
    fn a_viewer_can_gift_without_holding_a_seat() {
        let roster = roster();
        assert_eq!(roster.can_send_gift(555), Ok(()));
    }

    #[test]
    fn the_host_cannot_send_a_gift_into_their_own_room() {
        let roster = roster();
        assert_eq!(roster.can_send_gift(100), Err(Denial::HostCannotGiftToSelf));
    }

    #[test]
    fn a_seated_moderator_cannot_gift() {
        let mut roster = roster();
        roster
            .take_seat(200, 0, SeatRole::Moderator)
            .expect("seat taken");
        assert_eq!(roster.can_send_gift(200), Err(Denial::NotSeated));
        assert_eq!(roster.can_receive_gift(200), Err(Denial::NotSeated));
    }

    #[test]
    fn a_moderator_can_still_gift_after_being_demoted_to_guest() {
        let mut roster = roster();
        roster
            .take_seat(200, 0, SeatRole::Moderator)
            .expect("seat taken");
        roster.set_role(200, SeatRole::Guest).expect("role changed");
        assert_eq!(roster.can_send_gift(200), Ok(()));
    }

    #[test]
    fn the_host_always_receives_regardless_of_seating() {
        let roster = roster();
        assert_eq!(roster.can_receive_gift(100), Ok(()));
        assert_eq!(roster.can_send_gift(100), Err(Denial::HostCannotGiftToSelf));
    }

    #[test]
    fn seats_are_capped_at_the_room_capacity() {
        let mut roster = roster();
        for index in 0..MAX_SEATS {
            roster
                .take_seat(1000 + index as u64, index as u8, SeatRole::Guest)
                .expect("seat available");
        }
        assert_eq!(roster.seat_count(), MAX_SEATS);
        let overflow = roster.take_seat(9999, 0, SeatRole::Guest);
        assert!(matches!(
            overflow,
            Err(Denial::SeatOccupied) | Err(Denial::SeatFull)
        ));
    }

    #[test]
    fn an_account_cannot_hold_two_seats() {
        let mut roster = roster();
        roster
            .take_seat(200, 0, SeatRole::Guest)
            .expect("first seat");
        assert_eq!(
            roster.take_seat(200, 1, SeatRole::Guest),
            Err(Denial::AlreadySeated)
        );
    }

    #[test]
    fn leaving_frees_the_slot_for_someone_else() {
        let mut roster = roster();
        roster
            .take_seat(200, 3, SeatRole::Guest)
            .expect("seat taken");
        roster.leave(200).expect("left");
        assert_eq!(roster.seat_of(200), None);
        roster
            .take_seat(201, 3, SeatRole::CoHost)
            .expect("slot reused");
    }

    #[test]
    fn slot_numbers_outside_capacity_are_refused() {
        let mut roster = roster();
        assert_eq!(
            roster.take_seat(200, MAX_SEATS as u8, SeatRole::Guest),
            Err(Denial::SlotOutOfRange)
        );
    }

    #[test]
    fn viewer_count_is_kept_outside_the_seat_model() {
        let mut roster = roster();
        roster.set_viewer_count(48_000);
        assert_eq!(roster.viewer_count(), 48_000);
        assert_eq!(roster.seat_count(), 0);
    }

    #[test]
    fn any_verified_account_can_open_a_room_without_a_grant() {
        let mut roster = RoomRoster::new(1, 555);
        assert!(roster.is_host(555));
        assert_eq!(roster.seat_count(), 0);
        roster
            .take_seat(556, 0, SeatRole::CoHost)
            .expect("a room can start with just a host");
    }

    #[test]
    fn a_banned_or_unverified_account_cannot_publish() {
        let verified = AccountState::verified();
        assert!(verified.can_publish());

        assert!(!AccountState::new(true, false, false).can_publish());
        assert!(!AccountState::new(false, true, false).can_publish());
        assert!(!AccountState::new(true, true, true).can_publish());
    }

    #[test]
    fn publishing_eligibility_is_account_state_not_a_role() {
        let first_timer = AccountState::new(true, true, false);
        let veteran = AccountState::new(true, true, false);
        assert_eq!(first_timer.can_publish(), veteran.can_publish());
    }
}
