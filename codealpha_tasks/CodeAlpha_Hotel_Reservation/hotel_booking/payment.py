"""Simulated payment gateway.

No network calls and no real card data are involved. The gateway is
deterministic, which keeps tests stable:

* card numbers failing the Luhn checksum are rejected outright;
* any card ending in ``0000`` is "declined by the bank";
* every other well-formed card succeeds.

Only the last four digits of a card are ever stored.
"""

from __future__ import annotations

import secrets
from decimal import Decimal

from .models import Payment, PaymentStatus, money


class PaymentError(Exception):
    """Base class for payment failures."""


class PaymentDeclined(PaymentError):
    """The simulated bank declined the charge."""


class InvalidCardError(PaymentError):
    """Card details failed local validation."""


def luhn_valid(card_number: str) -> bool:
    digits = [int(ch) for ch in card_number if ch.isdigit()]
    if len(digits) < 12:
        return False
    checksum = 0
    for index, digit in enumerate(reversed(digits)):
        if index % 2 == 1:
            digit *= 2
            if digit > 9:
                digit -= 9
        checksum += digit
    return checksum % 10 == 0


class PaymentGateway:
    SUPPORTED_METHODS = {"card", "cash"}

    def charge(
        self,
        amount: Decimal,
        method: str = "card",
        card_number: str | None = None,
        card_holder: str = "",
    ) -> Payment:
        amount = money(amount)
        if amount <= 0:
            raise PaymentError("Charge amount must be positive")

        normalized = method.strip().lower()
        if normalized not in self.SUPPORTED_METHODS:
            raise PaymentError(
                f"Unsupported payment method {method!r}. "
                f"Use one of: {', '.join(sorted(self.SUPPORTED_METHODS))}"
            )

        last4 = ""
        if normalized == "cash":
            message = "Pay at the front desk on arrival"
        else:
            if not card_number:
                raise InvalidCardError("A card number is required for card payments")
            digits = "".join(ch for ch in card_number if ch.isdigit())
            if not luhn_valid(digits):
                raise InvalidCardError("Card number failed validation (checksum)")
            last4 = digits[-4:]
            if digits.endswith("0000"):
                raise PaymentDeclined("Card declined by the simulator (ends in 0000)")
            message = f"Charged card ending {last4}"

        return Payment(
            transaction_id=self._transaction_id(),
            amount=amount,
            method=normalized,
            status=PaymentStatus.PAID,
            card_last4=last4,
            message=message,
        )

    def refund(self, payment: Payment, amount: Decimal) -> Payment:
        """Refund up to the full charged amount; returns a new Payment record."""
        amount = money(amount)
        if amount <= 0:
            raise PaymentError("Refund amount must be positive")
        if amount > payment.amount:
            raise PaymentError(
                f"Cannot refund {amount} of a {payment.amount} charge"
            )
        status = (
            PaymentStatus.REFUNDED
            if amount == payment.amount
            else PaymentStatus.PARTIALLY_REFUNDED
        )
        return Payment(
            transaction_id=self._transaction_id(),
            amount=amount,
            method=payment.method,
            status=status,
            card_last4=payment.card_last4,
            message=f"Refunded {amount} to {payment.method}"
            + (f" ending {payment.card_last4}" if payment.card_last4 else ""),
        )

    @staticmethod
    def _transaction_id() -> str:
        return "TXN-" + secrets.token_hex(5).upper()


def demo_card_number(valid: bool = True) -> str:
    """Return a Luhn-valid test card (or a deliberately invalid one)."""
    base = "424242424242424"
    digits = [int(ch) for ch in base]
    checksum = 0
    for index, digit in enumerate(reversed(digits)):
        if index % 2 == 0:
            digit *= 2
            if digit > 9:
                digit -= 9
        checksum += digit
    check_digit = (10 - checksum % 10) % 10
    number = base + str(check_digit)
    if not valid:
        number = number[:-1] + str((int(number[-1]) + 1) % 10)
    return number
