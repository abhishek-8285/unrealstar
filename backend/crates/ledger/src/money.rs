use rust_decimal::prelude::*;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use std::fmt;
use thiserror::Error;

pub const SCALE: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Money(Decimal);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Error)]
pub enum MoneyError {
    #[error("money value overflows")]
    Overflow,
    #[error("money value has an unrepresentable scale")]
    InvalidScale,
    #[error("money value is not representable in minor units")]
    NotRepresentable,
}

impl Money {
    pub fn zero() -> Self {
        Money(Decimal::ZERO)
    }

    pub fn from_minor(minor: i64) -> Result<Self, MoneyError> {
        let decimal = Decimal::from(minor).checked_div(Decimal::from(100i64)).ok_or(MoneyError::Overflow)?;
        Ok(Money(decimal))
    }

    pub fn from_decimal(value: Decimal) -> Result<Self, MoneyError> {
        let scaled = value.checked_mul(Decimal::from(100i64)).ok_or(MoneyError::Overflow)?;
        let minor = scaled.to_i64().ok_or(MoneyError::NotRepresentable)?;
        Self::from_minor(minor)
    }

    pub fn to_minor(self) -> i64 {
        self.0
            .checked_mul(Decimal::from(100i64))
            .and_then(|scaled| scaled.to_i64())
            .unwrap_or(0)
    }

    pub fn checked_add(self, other: Money) -> Result<Money, MoneyError> {
        let sum = self.0.checked_add(other.0).ok_or(MoneyError::Overflow)?;
        Self::ensure_representable(sum)
    }

    pub fn checked_sub(self, other: Money) -> Result<Money, MoneyError> {
        let difference = self.0.checked_sub(other.0).ok_or(MoneyError::Overflow)?;
        Self::ensure_representable(difference)
    }

    fn ensure_representable(value: Decimal) -> Result<Money, MoneyError> {
        value
            .checked_mul(Decimal::from(100i64))
            .and_then(|scaled| scaled.to_i64())
            .ok_or(MoneyError::NotRepresentable)?;
        Ok(Money(value))
    }

    pub fn is_negative(self) -> bool {
        self.0.is_sign_negative() && !self.0.is_zero()
    }

    pub fn is_positive(self) -> bool {
        self.0.is_sign_positive() && !self.0.is_zero()
    }

    pub fn raw(self) -> Decimal {
        self.0
    }
}

impl fmt::Display for Money {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl Default for Money {
    fn default() -> Self {
        Money::zero()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn minor_units_round_trip() {
        let cases = [
            (0i64, 0i64),
            (1, 1),
            (99, 99),
            (100, 100),
            (i64::MAX, i64::MAX),
            (i64::MIN + 1, i64::MIN + 1),
        ];
        for (input, expected) in cases {
            let money = Money::from_minor(input).expect("valid minor amount");
            assert_eq!(money.to_minor(), expected, "round trip failed for {input}");
        }
    }

    #[test]
    fn negative_minor_units_round_trip() {
        let money = Money::from_minor(-250).expect("valid");
        assert_eq!(money.to_minor(), -250);
        assert!(money.is_negative());
    }

    #[test]
    fn decimal_input_normalizes_to_scale() {
        let money = Money::from_decimal(Decimal::new(12345, 2)).expect("valid");
        assert_eq!(money.to_minor(), 12345);
    }

    #[test]
    fn zero_decimal_input_is_zero() {
        let money = Money::from_decimal(Decimal::ZERO).expect("valid");
        assert_eq!(money.to_minor(), 0);
    }

    #[test]
    fn addition_and_subtraction_are_exact() {
        let tenth = Money::from_minor(10).expect("valid");
        let sum = tenth.checked_add(tenth).expect("no overflow");
        assert_eq!(sum.to_minor(), 20);
        let difference = sum.checked_sub(tenth).expect("no overflow");
        assert_eq!(difference.to_minor(), 10);
    }

    #[test]
    fn arithmetic_that_would_leave_the_i64_minor_domain_is_rejected() {
        let max = Money::from_minor(i64::MAX).expect("valid");
        let one = Money::from_minor(1).expect("valid");
        assert_eq!(max.checked_add(one), Err(MoneyError::NotRepresentable));
    }

    #[test]
    fn amounts_beyond_i64_minor_units_are_rejected_at_the_boundary() {
        let too_large = Decimal::from(i64::MAX).checked_add(Decimal::from(1)).expect("representable");
        assert_eq!(Money::from_decimal(too_large), Err(MoneyError::NotRepresentable));
    }

    #[test]
    fn i64_minor_unit_maximum_is_held_exactly() {
        let max = Money::from_minor(i64::MAX).expect("valid");
        assert_eq!(max.to_minor(), i64::MAX);
    }

    #[test]
    fn zero_is_neither_positive_nor_negative() {
        let zero = Money::zero();
        assert!(!zero.is_positive());
        assert!(!zero.is_negative());
        assert_eq!(zero.to_minor(), 0);
    }
}