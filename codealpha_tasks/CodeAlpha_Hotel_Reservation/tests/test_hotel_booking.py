"""Unit tests for the hotel booking domain, service and persistence."""

from __future__ import annotations

import tempfile
import unittest
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

from hotel_booking.models import Guest, ReservationStatus, RoomCategory
from hotel_booking.payment import (
    InvalidCardError,
    PaymentDeclined,
    demo_card_number,
    luhn_valid,
)
from hotel_booking.seed import default_rooms
from hotel_booking.service import BookingError, HotelService, RoomUnavailable


def future(days: int) -> date:
    return date.today() + timedelta(days=days)


GUEST = Guest("Ada Lovelace", "ada@example.com", "+1 555 0100")


def declined_luhn_valid_card() -> str:
    """Find a 16-digit card that is Luhn-valid but ends in 0000."""
    for prefix in ("42424242", "55555555", "37828224"):
        for middle in range(10000):
            candidate = f"{prefix}{middle:04d}0000"
            if luhn_valid(candidate):
                return candidate
    raise AssertionError("could not build a card ending in 0000")


class ServiceTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.data_dir = Path(self._tmp.name)
        self.service = HotelService(self.data_dir)
        self.service.rooms.seed_if_empty(default_rooms())

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def book(self, *args, **kwargs):
        """Book with a valid test card unless the test overrides it."""
        kwargs.setdefault("card_number", demo_card_number())
        return self.service.book(*args, **kwargs)

    # ------------------------------------------------------------------ search

    def test_search_returns_only_available_matching_rooms(self) -> None:
        offers = self.service.search(future(1), future(3), guests=2)
        self.assertTrue(offers)
        self.assertTrue(all(o.room.category.max_occupancy >= 2 for o in offers))
        self.assertTrue(all(o.nights == 2 for o in offers))

    def test_search_filters_by_category_and_price(self) -> None:
        offers = self.service.search(
            future(1), future(3), guests=2, category=RoomCategory.SUITE
        )
        self.assertTrue(offers)
        self.assertTrue(all(o.room.category is RoomCategory.SUITE for o in offers))

        cheap = self.service.search(future(1), future(3), max_nightly_rate=Decimal("100"))
        self.assertTrue(all(o.nightly_rate <= 100 for o in cheap))
        self.assertTrue(cheap)

    def test_search_excludes_room_too_small_for_party(self) -> None:
        offers = self.service.search(future(1), future(2), guests=4)
        self.assertTrue(offers)
        self.assertTrue(all(o.room.category is RoomCategory.SUITE for o in offers))

    def test_search_rejects_invalid_stay(self) -> None:
        with self.assertRaises(BookingError):
            self.service.search(future(3), future(3))
        with self.assertRaises(BookingError):
            self.service.search(future(-5), future(-1))

    def test_adjacent_stays_are_not_conflicting(self) -> None:
        self.book("101", GUEST, future(1), future(3), guests=1)
        # The room turns over on the checkout day, so this is allowed.
        self.assertTrue(self.service.is_available("101", future(3), future(5)))
        # Overlapping stays are not.
        self.assertFalse(self.service.is_available("101", future(2), future(4)))

    # -------------------------------------------------------------------- book

    def test_booking_charges_total_and_persists(self) -> None:
        reservation = self.book(
            "201", GUEST, future(1), future(4), guests=2, card_number=demo_card_number()
        )
        self.assertEqual(reservation.nights, 3)
        self.assertEqual(
            reservation.subtotal,
            (RoomCategory.DELUXE.base_rate * 3).quantize(Decimal("0.01")),
        )
        self.assertEqual(
            reservation.total,
            reservation.subtotal + reservation.tax,
        )
        self.assertEqual(reservation.payment.status.value, "PAID")
        self.assertEqual(reservation.payment.amount, reservation.total)
        self.assertEqual(len(reservation.payment.card_last4), 4)

        reloaded = HotelService(self.data_dir)
        stored = reloaded.get_reservation(reservation.id)
        self.assertEqual(stored.guest.email, GUEST.email)
        self.assertEqual(stored.total, reservation.total)
        self.assertEqual(stored.room_number, "201")

    def test_double_booking_is_rejected(self) -> None:
        self.book("101", GUEST, future(1), future(4))
        with self.assertRaises(RoomUnavailable):
            self.book("101", GUEST, future(3), future(6))
        self.assertEqual(len(self.service.reservations.all()), 1)

    def test_capacity_violation_is_rejected(self) -> None:
        with self.assertRaises(BookingError):
            self.book("101", GUEST, future(1), future(2), guests=3)

    def test_invalid_guest_email_is_rejected(self) -> None:
        with self.assertRaises(BookingError):
            self.book("101", Guest("No Email", "nope"), future(1), future(2))

    def test_declined_card_creates_no_reservation(self) -> None:
        with self.assertRaises(PaymentDeclined):
            self.book(
                "101", GUEST, future(1), future(2),
                card_number=declined_luhn_valid_card(),
            )
        self.assertEqual(self.service.reservations.all(), [])

    def test_invalid_card_number_is_rejected(self) -> None:
        with self.assertRaises(InvalidCardError):
            self.book("101", GUEST, future(1), future(2), card_number="1234")

    def test_cash_payment_records_method(self) -> None:
        reservation = self.book(
            "101", GUEST, future(1), future(2), payment_method="cash"
        )
        self.assertEqual(reservation.payment.method, "cash")
        self.assertEqual(reservation.payment.card_last4, "")

    # ------------------------------------------------------------------ cancel

    def test_early_cancellation_refunds_in_full_and_frees_room(self) -> None:
        reservation = self.book("101", GUEST, future(10), future(12))
        refund = self.service.quote_refund_preview(reservation.id, future(1))
        self.assertEqual(refund, reservation.total)

        cancelled = self.service.cancel(reservation.id, on_date=future(1))
        self.assertIs(cancelled.status, ReservationStatus.CANCELLED)
        self.assertEqual(cancelled.refunded, reservation.total)
        self.assertEqual(cancelled.refund_payment.status.value, "REFUNDED")
        self.assertTrue(self.service.is_available("101", future(10), future(12)))

    def test_late_cancellation_refunds_half(self) -> None:
        reservation = self.book("101", GUEST, future(5), future(7))
        cancelled = self.service.cancel(reservation.id, on_date=future(4))
        expected = (reservation.total * Decimal("0.50")).quantize(Decimal("0.01"))
        self.assertEqual(cancelled.refunded, expected)
        self.assertEqual(
            cancelled.refund_payment.status.value, "PARTIALLY_REFUNDED"
        )

    def test_cancelling_twice_is_rejected(self) -> None:
        reservation = self.book("101", GUEST, future(10), future(11))
        self.service.cancel(reservation.id, on_date=future(1))
        with self.assertRaises(BookingError):
            self.service.cancel(reservation.id, on_date=future(1))

    def test_cannot_cancel_after_arrival(self) -> None:
        reservation = self.book("101", GUEST, future(5), future(7))
        with self.assertRaises(BookingError):
            self.service.cancel(reservation.id, on_date=future(5))

    def test_unknown_reservation_id_is_rejected(self) -> None:
        with self.assertRaises(BookingError):
            self.service.cancel("RSV-NOPE", on_date=future(1))

    # --------------------------------------------------------------- reporting

    def test_occupancy_and_revenue_summary(self) -> None:
        self.book("101", GUEST, future(2), future(4))
        self.book("102", GUEST, future(2), future(3))
        report = self.service.occupancy(future(2))
        self.assertEqual(report["occupied"], 2)
        self.assertEqual(sorted(report["room_numbers"]), ["101", "102"])

        summary = self.service.revenue_summary()
        self.assertEqual(summary["confirmed_bookings"], 2)
        self.assertGreater(summary["net"], 0)

    def test_reservation_lookup_by_email(self) -> None:
        self.book("101", GUEST, future(2), future(3))
        found = self.service.reservations_for("ADA@example.com")
        self.assertEqual(len(found), 1)


class PersistenceTestCase(unittest.TestCase):
    def test_seed_runs_only_once_and_survives_reload(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            service = HotelService(Path(tmp))
            self.assertTrue(service.rooms.seed_if_empty(default_rooms()))
            self.assertFalse(service.rooms.seed_if_empty(default_rooms()))
            self.assertEqual(len(service.rooms.all()), 11)

            reloaded = HotelService(Path(tmp))
            self.assertEqual(len(reloaded.rooms.all()), 11)

    def test_corrupted_file_raises_storage_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rooms.json"
            path.write_text("{not json", encoding="utf-8")
            from hotel_booking.storage import StorageError

            with self.assertRaises(StorageError):
                HotelService(Path(tmp))


if __name__ == "__main__":
    unittest.main()
