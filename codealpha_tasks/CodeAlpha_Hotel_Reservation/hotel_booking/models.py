"""Domain model for the hotel booking system.

Money is handled with ``Decimal`` and serialized as strings so that totals
survive a JSON round-trip without floating-point drift.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum
from typing import Any, Iterable


MONEY = Decimal("0.01")


def money(value: Decimal | str | int | float) -> Decimal:
    """Quantize a value to two decimal places."""
    return Decimal(str(value)).quantize(MONEY, rounding=ROUND_HALF_UP)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class RoomCategory(str, Enum):
    """Room categories with their commercial characteristics."""

    STANDARD = "Standard"
    DELUXE = "Deluxe"
    SUITE = "Suite"

    @property
    def base_rate(self) -> Decimal:
        return {
            RoomCategory.STANDARD: Decimal("99.00"),
            RoomCategory.DELUXE: Decimal("159.00"),
            RoomCategory.SUITE: Decimal("289.00"),
        }[self]

    @property
    def max_occupancy(self) -> int:
        return {
            RoomCategory.STANDARD: 2,
            RoomCategory.DELUXE: 3,
            RoomCategory.SUITE: 5,
        }[self]

    @classmethod
    def from_value(cls, value: str) -> "RoomCategory":
        """Case-insensitive lookup used by the CLI and by JSON loading."""
        normalized = str(value).strip().lower()
        for category in cls:
            if category.value.lower() == normalized or category.name.lower() == normalized:
                return category
        raise ValueError(
            f"Unknown room category {value!r}. Choose one of: "
            + ", ".join(c.value for c in cls)
        )


@dataclass(frozen=True)
class Room:
    """A physical room. Availability is derived from reservations, never stored."""

    number: str
    category: RoomCategory
    floor: int
    amenities: tuple[str, ...] = ()
    active: bool = True

    @property
    def nightly_rate(self) -> Decimal:
        return self.category.base_rate

    def to_dict(self) -> dict[str, Any]:
        return {
            "number": self.number,
            "category": self.category.value,
            "floor": self.floor,
            "amenities": list(self.amenities),
            "active": self.active,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Room":
        return cls(
            number=str(data["number"]),
            category=RoomCategory.from_value(data["category"]),
            floor=int(data.get("floor", 1)),
            amenities=tuple(data.get("amenities", ())),
            active=bool(data.get("active", True)),
        )


@dataclass(frozen=True)
class Guest:
    name: str
    email: str
    phone: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {"name": self.name, "email": self.email, "phone": self.phone}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Guest":
        return cls(
            name=str(data["name"]),
            email=str(data["email"]),
            phone=str(data.get("phone", "")),
        )


class ReservationStatus(str, Enum):
    CONFIRMED = "CONFIRMED"
    CANCELLED = "CANCELLED"


class PaymentStatus(str, Enum):
    PAID = "PAID"
    REFUNDED = "REFUNDED"
    PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED"
    FAILED = "FAILED"


@dataclass
class Payment:
    """Result of a simulated payment gateway transaction."""

    transaction_id: str
    amount: Decimal
    method: str
    status: PaymentStatus
    card_last4: str = ""
    message: str = ""
    created_at: datetime = field(default_factory=utcnow)

    def to_dict(self) -> dict[str, Any]:
        return {
            "transaction_id": self.transaction_id,
            "amount": str(self.amount),
            "method": self.method,
            "status": self.status.value,
            "card_last4": self.card_last4,
            "message": self.message,
            "created_at": self.created_at.isoformat(),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Payment":
        return cls(
            transaction_id=str(data["transaction_id"]),
            amount=money(data["amount"]),
            method=str(data["method"]),
            status=PaymentStatus(data["status"]),
            card_last4=str(data.get("card_last4", "")),
            message=str(data.get("message", "")),
            created_at=datetime.fromisoformat(data["created_at"]),
        )


@dataclass
class Reservation:
    """A booking of one room for a half-open date range ``[check_in, check_out)``."""

    id: str
    room_number: str
    guest: Guest
    check_in: date
    check_out: date
    guests: int
    nightly_rate: Decimal
    subtotal: Decimal
    tax: Decimal
    total: Decimal
    status: ReservationStatus = ReservationStatus.CONFIRMED
    payment: Payment | None = None
    refund_payment: Payment | None = None
    refunded: Decimal = Decimal("0.00")
    created_at: datetime = field(default_factory=utcnow)
    cancelled_at: datetime | None = None

    @staticmethod
    def new_id() -> str:
        return "RSV-" + uuid.uuid4().hex[:8].upper()

    @property
    def nights(self) -> int:
        return (self.check_out - self.check_in).days

    def overlaps(self, other_start: date, other_end: date) -> bool:
        """Half-open interval overlap test: checkout day is free for the next guest."""
        return self.check_in < other_end and other_start < self.check_out

    @property
    def is_active(self) -> bool:
        return self.status is ReservationStatus.CONFIRMED

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "room_number": self.room_number,
            "guest": self.guest.to_dict(),
            "check_in": self.check_in.isoformat(),
            "check_out": self.check_out.isoformat(),
            "guests": self.guests,
            "nightly_rate": str(self.nightly_rate),
            "subtotal": str(self.subtotal),
            "tax": str(self.tax),
            "total": str(self.total),
            "status": self.status.value,
            "payment": self.payment.to_dict() if self.payment else None,
            "refund_payment": self.refund_payment.to_dict() if self.refund_payment else None,
            "refunded": str(self.refunded),
            "created_at": self.created_at.isoformat(),
            "cancelled_at": self.cancelled_at.isoformat() if self.cancelled_at else None,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Reservation":
        cancelled_at = data.get("cancelled_at")
        return cls(
            id=str(data["id"]),
            room_number=str(data["room_number"]),
            guest=Guest.from_dict(data["guest"]),
            check_in=date.fromisoformat(data["check_in"]),
            check_out=date.fromisoformat(data["check_out"]),
            guests=int(data["guests"]),
            nightly_rate=money(data["nightly_rate"]),
            subtotal=money(data["subtotal"]),
            tax=money(data["tax"]),
            total=money(data["total"]),
            status=ReservationStatus(data["status"]),
            payment=Payment.from_dict(data["payment"]) if data.get("payment") else None,
            refund_payment=(
                Payment.from_dict(data["refund_payment"])
                if data.get("refund_payment")
                else None
            ),
            refunded=money(data.get("refunded", "0")),
            created_at=datetime.fromisoformat(data["created_at"]),
            cancelled_at=datetime.fromisoformat(cancelled_at) if cancelled_at else None,
        )


@dataclass(frozen=True)
class RoomOffer:
    """A priced search result: a room that is free for the requested stay."""

    room: Room
    nights: int
    nightly_rate: Decimal
    subtotal: Decimal
    tax: Decimal
    total: Decimal

    @classmethod
    def build(cls, room: Room, nights: int, tax_rate: Decimal) -> "RoomOffer":
        subtotal = money(room.nightly_rate * nights)
        tax = money(subtotal * tax_rate)
        return cls(
            room=room,
            nights=nights,
            nightly_rate=room.nightly_rate,
            subtotal=subtotal,
            tax=tax,
            total=money(subtotal + tax),
        )


def ranges_overlap(
    start_a: date, end_a: date, start_b: date, end_b: date
) -> bool:
    return start_a < end_b and start_b < end_a


def any_overlap(intervals: Iterable[tuple[date, date]], start: date, end: date) -> bool:
    return any(ranges_overlap(s, e, start, end) for s, e in intervals)
