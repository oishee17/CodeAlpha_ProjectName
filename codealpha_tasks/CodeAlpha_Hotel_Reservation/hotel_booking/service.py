"""Application service: search, book, cancel and report.

This is the only layer that mutates state. It enforces every business rule:

* a room must be active and big enough for the party;
* the same room can never be double-booked for overlapping dates;
* money is charged before a reservation is written;
* cancellation refunds depend on how far ahead of arrival you cancel.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

from .models import (
    Guest,
    Payment,
    Reservation,
    ReservationStatus,
    Room,
    RoomCategory,
    RoomOffer,
    money,
    utcnow,
)
from .payment import PaymentError, PaymentGateway
from .storage import JsonCollection, ReservationRepository, RoomRepository


class BookingError(Exception):
    """A booking rule was violated (bad dates, party too large, ...)."""


class RoomUnavailable(BookingError):
    """The requested room is already booked for part of the requested stay."""


class HotelService:
    """Facade over the room inventory, reservation store and payment gateway."""

    TAX_RATE = Decimal("0.10")
    #: Cancelling at least this many days before arrival is fully refunded.
    FULL_REFUND_DAYS = 2
    #: Refund share applied to late cancellations.
    LATE_REFUND_RATE = Decimal("0.50")

    def __init__(
        self,
        data_dir: str | Path = "data",
        gateway: PaymentGateway | None = None,
    ) -> None:
        self.data_dir = Path(data_dir)
        self.gateway = gateway or PaymentGateway()
        self.rooms = RoomRepository(JsonCollection(self.data_dir / "rooms.json"))
        self.reservations = ReservationRepository(
            JsonCollection(self.data_dir / "reservations.json")
        )

    # ------------------------------------------------------------------ search

    def search(
        self,
        check_in: date,
        check_out: date,
        guests: int = 1,
        category: RoomCategory | None = None,
        max_nightly_rate: Decimal | None = None,
    ) -> list[RoomOffer]:
        """Return available rooms for the stay, cheapest first."""
        self.validate_stay(check_in, check_out, guests)
        nights = (check_out - check_in).days

        offers: list[RoomOffer] = []
        for room in self.rooms.active():
            if category is not None and room.category is not category:
                continue
            if guests > room.category.max_occupancy:
                continue
            if max_nightly_rate is not None and room.nightly_rate > max_nightly_rate:
                continue
            if not self.is_available(room.number, check_in, check_out):
                continue
            offers.append(RoomOffer.build(room, nights, self.TAX_RATE))
        offers.sort(key=lambda offer: (offer.total, offer.room.number))
        return offers

    def is_available(
        self,
        room_number: str,
        check_in: date,
        check_out: date,
        ignore_reservation_id: str | None = None,
    ) -> bool:
        room = self.rooms.get(room_number)
        if room is None or not room.active:
            return False
        for reservation in self.reservations.for_room(room_number):
            if not reservation.is_active:
                continue
            if ignore_reservation_id and reservation.id == ignore_reservation_id:
                continue
            if reservation.overlaps(check_in, check_out):
                return False
        return True

    def unavailable_rooms(self, check_in: date, check_out: date) -> list[str]:
        return [
            room.number
            for room in self.rooms.active()
            if not self.is_available(room.number, check_in, check_out)
        ]

    # -------------------------------------------------------------------- book

    def quote(
        self, room_number: str, check_in: date, check_out: date, guests: int = 1
    ) -> RoomOffer:
        room = self._require_bookable(room_number, check_in, check_out, guests)
        if not self.is_available(room.number, check_in, check_out):
            raise RoomUnavailable(
                f"Room {room.number} is already booked for {check_in} to {check_out}"
            )
        return RoomOffer.build(room, (check_out - check_in).days, self.TAX_RATE)

    def book(
        self,
        room_number: str,
        guest: Guest,
        check_in: date,
        check_out: date,
        guests: int = 1,
        payment_method: str = "card",
        card_number: str | None = None,
    ) -> Reservation:
        """Charge the guest and persist a confirmed reservation."""
        offer = self.quote(room_number, check_in, check_out, guests)
        self._validate_guest(guest)

        payment = self.gateway.charge(
            offer.total,
            method=payment_method,
            card_number=card_number,
            card_holder=guest.name,
        )

        reservation = Reservation(
            id=Reservation.new_id(),
            room_number=offer.room.number,
            guest=guest,
            check_in=check_in,
            check_out=check_out,
            guests=guests,
            nightly_rate=offer.nightly_rate,
            subtotal=offer.subtotal,
            tax=offer.tax,
            total=offer.total,
            status=ReservationStatus.CONFIRMED,
            payment=payment,
        )

        # Re-check right before persisting so a concurrent booking cannot slip in.
        if not self.is_available(room_number, check_in, check_out):
            self.gateway.refund(payment, payment.amount)
            raise RoomUnavailable(
                f"Room {room_number} was taken while the payment was processed; "
                "the charge has been refunded"
            )

        self.reservations.add(reservation)
        return reservation

    # ------------------------------------------------------------------ cancel

    def cancel(self, reservation_id: str, on_date: date | None = None) -> Reservation:
        reservation = self.get_reservation(reservation_id)
        if not reservation.is_active:
            raise BookingError(f"Reservation {reservation.id} is already cancelled")

        today = on_date or date.today()
        if reservation.check_in <= today:
            raise BookingError(
                "Stays that have already started cannot be cancelled online; "
                "please contact the front desk"
            )

        refund_amount = self.refund_amount(reservation, today)
        if reservation.payment and refund_amount > 0:
            refund_payment = self.gateway.refund(reservation.payment, refund_amount)
            reservation.refund_payment = refund_payment
            reservation.refunded = refund_amount

        reservation.status = ReservationStatus.CANCELLED
        reservation.cancelled_at = utcnow()
        self.reservations.update(reservation)
        return reservation

    def refund_amount(self, reservation: Reservation, on_date: date) -> Decimal:
        """Full refund when cancelling early enough, half otherwise."""
        days_ahead = (reservation.check_in - on_date).days
        rate = Decimal("1.00") if days_ahead >= self.FULL_REFUND_DAYS else self.LATE_REFUND_RATE
        return money(reservation.total * rate)

    # ------------------------------------------------------------------ lookups

    def get_reservation(self, reservation_id: str) -> Reservation:
        reservation = self.reservations.get(reservation_id)
        if reservation is None:
            raise BookingError(f"No reservation found with id {reservation_id!r}")
        return reservation

    def reservations_for(self, email: str) -> list[Reservation]:
        return self.reservations.for_email(email)

    def quote_refund_preview(self, reservation_id: str, on_date: date | None = None) -> Decimal:
        return self.refund_amount(self.get_reservation(reservation_id), on_date or date.today())

    # ------------------------------------------------------------------ reporting

    def occupancy(self, day: date) -> dict[str, object]:
        """Simple front-desk report for a single night."""
        total = len(self.rooms.active())
        occupied = [
            r
            for r in self.reservations.all()
            if r.is_active and r.check_in <= day < r.check_out
        ]
        revenue = money(sum((r.nightly_rate for r in occupied), Decimal("0")))
        return {
            "date": day,
            "rooms": total,
            "occupied": len(occupied),
            "available": total - len(occupied),
            "occupancy_rate": round(len(occupied) / total, 3) if total else 0.0,
            "room_revenue": revenue,
            "room_numbers": [r.room_number for r in occupied],
        }

    def revenue_summary(self) -> dict[str, Decimal | int]:
        confirmed = [r for r in self.reservations.all() if r.is_active]
        refunded = money(
            sum((r.refunded for r in self.reservations.all()), Decimal("0"))
        )
        collected = money(sum((r.total for r in confirmed), Decimal("0")))
        return {
            "confirmed_bookings": len(confirmed),
            "cancelled_bookings": len(self.reservations.all()) - len(confirmed),
            "collected": collected,
            "refunded": refunded,
            "net": money(collected - refunded),
        }

    def categories(self) -> list[RoomCategory]:
        return list(RoomCategory)

    # ------------------------------------------------------------------ helpers

    def validate_stay(self, check_in: date, check_out: date, guests: int) -> None:
        if check_out <= check_in:
            raise BookingError("Check-out date must be after the check-in date")
        if check_in < date.today():
            raise BookingError("Check-in date cannot be in the past")
        if guests < 1:
            raise BookingError("At least one guest is required")

    def _require_bookable(
        self, room_number: str, check_in: date, check_out: date, guests: int
    ) -> Room:
        self.validate_stay(check_in, check_out, guests)
        room = self.rooms.get(room_number)
        if room is None:
            raise BookingError(f"Room {room_number!r} does not exist")
        if not room.active:
            raise BookingError(f"Room {room.number} is out of service")
        if guests > room.category.max_occupancy:
            raise BookingError(
                f"Room {room.number} ({room.category.value}) holds at most "
                f"{room.category.max_occupancy} guests"
            )
        return room

    @staticmethod
    def _validate_guest(guest: Guest) -> None:
        if not guest.name.strip():
            raise BookingError("Guest name is required")
        if "@" not in guest.email or guest.email.startswith("@"):
            raise BookingError(f"Invalid guest email {guest.email!r}")


def parse_date(value: str) -> date:
    """Parse ``YYYY-MM-DD`` and raise a friendly BookingError otherwise."""
    try:
        return date.fromisoformat(value.strip())
    except (ValueError, AttributeError):
        raise BookingError(f"Invalid date {value!r}; expected format YYYY-MM-DD") from None


__all__ = [
    "BookingError",
    "HotelService",
    "PaymentError",
    "RoomUnavailable",
    "parse_date",
]
