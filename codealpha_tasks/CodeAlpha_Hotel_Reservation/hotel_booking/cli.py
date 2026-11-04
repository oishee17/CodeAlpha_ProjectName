"""Interactive console front end.

All business rules live in :class:`hotel_booking.service.HotelService`; this
module only collects input, formats output and handles user errors.
"""

from __future__ import annotations

import os
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation

from .models import Guest, Reservation, ReservationStatus, RoomCategory, RoomOffer
from .payment import PaymentError, demo_card_number
from .seed import default_rooms
from .service import BookingError, HotelService, parse_date
from .storage import StorageError

LINE = "-" * 74
THIN = "-" * 74


def money_str(value: Decimal) -> str:
    return f"${value:,.2f}"


def ask(prompt: str, default: str | None = None) -> str:
    suffix = f" [{default}]" if default else ""
    try:
        answer = input(f"{prompt}{suffix}: ").strip()
    except EOFError:
        raise SystemExit("\nInput closed. Goodbye.")
    return answer or (default or "")


def ask_yes_no(prompt: str, default: bool = False) -> bool:
    default_text = "y" if default else "n"
    answer = ask(f"{prompt} (y/n)", default_text).lower()
    return answer.startswith("y")


def ask_date(prompt: str, default: date | None = None) -> date:
    default_text = default.isoformat() if default else None
    return parse_date(ask(prompt, default_text))


def ask_int(prompt: str, default: int, minimum: int = 1) -> int:
    raw = ask(prompt, str(default))
    try:
        value = int(raw)
    except ValueError:
        raise BookingError(f"Expected a whole number, got {raw!r}") from None
    if value < minimum:
        raise BookingError(f"Value must be at least {minimum}")
    return value


def ask_money(prompt: str, default: Decimal | None = None) -> Decimal | None:
    raw = ask(prompt, str(default) if default is not None else "")
    if not raw:
        return None
    try:
        return Decimal(raw)
    except InvalidOperation:
        raise BookingError(f"Expected an amount, got {raw!r}") from None


def print_offer(offer: RoomOffer) -> None:
    room = offer.room
    print(
        f"  {room.number:<4} {room.category.value:<9} floor {room.floor}  "
        f"{money_str(offer.nightly_rate)}/night  "
        f"{offer.nights} night(s)  total {money_str(offer.total)}"
    )
    print(f"       sleeps {room.category.max_occupancy}, {', '.join(room.amenities)}")


def print_offers(offers: list[RoomOffer], check_in: date, check_out: date, guests: int) -> None:
    nights = (check_out - check_in).days
    print(f"\n{len(offers)} room(s) available {check_in} -> {check_out} "
          f"({nights} night(s), {guests} guest(s)):")
    if not offers:
        print("  No rooms match. Try different dates, fewer guests or another category.")
        return
    for offer in offers:
        print_offer(offer)
    cheapest = offers[0]
    print(f"\n  Cheapest: room {cheapest.room.number} at {money_str(cheapest.total)} "
          f"including {money_str(cheapest.tax)} tax.")


def print_reservation(reservation: Reservation, service: HotelService | None = None) -> None:
    room = service.rooms.get(reservation.room_number) if service else None
    category = room.category.value if room else "?"
    status = reservation.status.value
    if reservation.status is ReservationStatus.CONFIRMED and reservation.check_in <= date.today():
        status += " (in progress)"

    print(LINE)
    print(f"Reservation {reservation.id}  [{status}]")
    print(LINE)
    print(f"Guest        {reservation.guest.name} <{reservation.guest.email}>"
          + (f"  {reservation.guest.phone}" if reservation.guest.phone else ""))
    print(f"Room         {reservation.room_number} ({category})")
    print(f"Check-in     {reservation.check_in}")
    print(f"Check-out    {reservation.check_out}  ({reservation.nights} night(s))")
    print(f"Guests       {reservation.guests}")
    print(THIN)
    print(f"Nightly rate {money_str(reservation.nightly_rate)} x {reservation.nights}")
    print(f"Subtotal     {money_str(reservation.subtotal)}")
    print(f"Taxes        {money_str(reservation.tax)}")
    print(f"Total        {money_str(reservation.total)}")

    payment = reservation.payment
    if payment:
        card = f" ending {payment.card_last4}" if payment.card_last4 else ""
        print(f"Payment      {payment.status.value} - {payment.method}{card} "
              f"({payment.transaction_id})")
        if payment.message:
            print(f"             {payment.message}")
    if reservation.refund_payment:
        print(f"Refund       {money_str(reservation.refund_payment.amount)} "
              f"({reservation.refund_payment.transaction_id}) on "
              f"{reservation.refund_payment.created_at:%Y-%m-%d %H:%M} UTC")
    if reservation.cancelled_at:
        print(f"Cancelled    {reservation.cancelled_at:%Y-%m-%d %H:%M} UTC")
    print(f"Booked       {reservation.created_at:%Y-%m-%d %H:%M} UTC")
    print(LINE)


class HotelCLI:
    def __init__(self, service: HotelService) -> None:
        self.service = service

    # ------------------------------------------------------------------ menu

    def run(self) -> None:
        print("=" * 74)
        print("  GRAND MERIDIAN HOTEL - Reservation Desk")
        print("=" * 74)
        while True:
            print(
                "\n1) Search available rooms\n"
                "2) Book a room\n"
                "3) View booking details\n"
                "4) Cancel a reservation\n"
                "5) Find my bookings\n"
                "6) Occupancy and revenue report\n"
                "0) Exit"
            )
            choice = ask("Choose an option", "1")
            actions = {
                "1": self.search,
                "2": self.book,
                "3": self.view_booking,
                "4": self.cancel,
                "5": self.my_bookings,
                "6": self.report,
            }
            if choice in {"0", "q", "quit", "exit"}:
                print("Goodbye.")
                return
            action = actions.get(choice)
            if action is None:
                print("Unknown option.")
                continue
            try:
                action()
            except (BookingError, PaymentError, StorageError, ValueError) as exc:
                print(f"\n  ! {exc}")
            except (KeyboardInterrupt, EOFError):
                print("\nCancelled. Goodbye.")
                return

    # --------------------------------------------------------------- actions

    def search(self) -> None:
        today = date.today()
        check_in = ask_date("Check-in date (YYYY-MM-DD)", today + timedelta(days=1))
        check_out = ask_date("Check-out date (YYYY-MM-DD)", check_in + timedelta(days=2))
        guests = ask_int("Number of guests", 1)
        raw_category = ask(
            "Category (Standard/Deluxe/Suite, or 'any')", "any"
        )
        category = None
        if raw_category.lower() not in {"", "any", "all"}:
            category = RoomCategory.from_value(raw_category)
        max_rate = ask_money("Max nightly rate (blank for no limit)")

        offers = self.service.search(
            check_in, check_out, guests=guests, category=category,
            max_nightly_rate=max_rate,
        )
        print_offers(offers, check_in, check_out, guests)

    def book(self) -> None:
        today = date.today()
        check_in = ask_date("Check-in date (YYYY-MM-DD)", today + timedelta(days=1))
        check_out = ask_date("Check-out date (YYYY-MM-DD)", check_in + timedelta(days=2))
        guests = ask_int("Number of guests", 1)
        offers = self.service.search(check_in, check_out, guests=guests)
        print_offers(offers, check_in, check_out, guests)
        if not offers:
            return

        room_number = ask("Room number to book", offers[0].room.number)
        offer = self.service.quote(room_number, check_in, check_out, guests)

        print("\nGuest details:")
        name = ask("Full name")
        email = ask("Email")
        phone = ask("Phone (optional)", "")
        guest = Guest(name=name, email=email, phone=phone)

        method = ask("Payment method (card/cash)", "card").lower()
        card_number = None
        if method == "card":
            print(f"  Simulated gateway. Test card: {demo_card_number()} "
                  "(any card ending 0000 is declined).")
            card_number = ask("Card number", demo_card_number())

        print(f"\nCharging {money_str(offer.total)} for room {offer.room.number}...")
        reservation = self.service.book(
            room_number=room_number,
            guest=guest,
            check_in=check_in,
            check_out=check_out,
            guests=guests,
            payment_method=method,
            card_number=card_number,
        )
        print("\nBooking confirmed!")
        print_reservation(reservation, self.service)

    def view_booking(self) -> None:
        reservation_id = ask("Reservation id (e.g. RSV-1A2B3C4D)")
        reservation = self.service.get_reservation(reservation_id)
        print_reservation(reservation, self.service)

    def cancel(self) -> None:
        reservation_id = ask("Reservation id to cancel")
        reservation = self.service.get_reservation(reservation_id)
        if not reservation.is_active:
            raise BookingError(f"Reservation {reservation.id} is already cancelled")
        print_reservation(reservation, self.service)
        refund = self.service.quote_refund_preview(reservation.id)
        print(f"Refund if cancelled now: {money_str(refund)}")
        if not ask_yes_no("Cancel this reservation?", False):
            print("Nothing changed.")
            return
        cancelled = self.service.cancel(reservation.id)
        print("\nReservation cancelled.")
        print_reservation(cancelled, self.service)

    def my_bookings(self) -> None:
        email = ask("Email used for the booking")
        reservations = self.service.reservations_for(email)
        if not reservations:
            print("No reservations found for that email.")
            return
        print(f"\n{len(reservations)} reservation(s) for {email}:")
        for r in sorted(reservations, key=lambda x: x.check_in, reverse=True):
            print(
                f"  {r.id}  {r.status.value:<9} room {r.room_number:<4} "
                f"{r.check_in} -> {r.check_out}  {money_str(r.total)}"
            )

    def report(self) -> None:
        day = ask_date("Date for occupancy report", date.today())
        occupancy = self.service.occupancy(day)
        summary = self.service.revenue_summary()
        print(f"\nOccupancy for {occupancy['date']}")
        print(f"  Rooms       {occupancy['rooms']}")
        print(f"  Occupied    {occupancy['occupied']} "
              f"({occupancy['occupancy_rate'] * 100:.0f}%)")
        print(f"  Available   {occupancy['available']}")
        print(f"  Room revenue {money_str(occupancy['room_revenue'])}")
        print("\nBookings")
        print(f"  Confirmed   {summary['confirmed_bookings']}")
        print(f"  Cancelled   {summary['cancelled_bookings']}")
        print(f"  Collected   {money_str(summary['collected'])}")
        print(f"  Refunded    {money_str(summary['refunded'])}")
        print(f"  Net         {money_str(summary['net'])}")


def main(data_dir: str | None = None) -> None:
    service = HotelService(data_dir or os.environ.get("HOTEL_DATA_DIR", "data"))
    if service.rooms.seed_if_empty(default_rooms()):
        print(f"Seeded {len(service.rooms.all())} rooms into the inventory.")
    HotelCLI(service).run()
