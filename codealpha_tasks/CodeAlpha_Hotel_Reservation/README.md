# Grand Meridian Hotel — Reservation System

An object-oriented hotel booking system: search rooms by date, category, party
size and budget; book with a simulated payment; view booking details; cancel
with an automatic refund. Rooms and reservations are persisted to JSON files,
so all state survives restarts. Pure Python 3.10+ standard library — no
dependencies.

## Quick start

```bash
python main.py                 # uses ./data
python main.py --data-dir /tmp/hotel-demo
python -m unittest discover -s tests -v
```

On first run the 11-room demo inventory is seeded into `data/rooms.json`.
The menu covers search, booking, booking details, cancellation, "find my
bookings" by email, and an occupancy/revenue report.

## Design

```
                 ┌──────────────────────────┐
                 │          CLI             │  input/output only
                 │      (cli.py)            │
                 └────────────┬─────────────┘
                              │ calls
                 ┌────────────▼─────────────┐
                 │      HotelService        │  business rules, transactions
                 │      (service.py)        │
                 └───┬──────────┬───────┬───┘
                     │          │       │
        ┌────────────▼──┐  ┌────▼─────┐ │
        │ RoomRepository│  │Reservation│ │  ┌───────────────┐
        │               │  │Repository │ └─▶│PaymentGateway │
        └───────┬───────┘  └─────┬─────┘    │ (payment.py)  │
                │                │          └───────────────┘
        ┌───────▼───────┐  ┌─────▼─────┐
        │  rooms.json   │  │reservations│
        │  (JsonStore)  │  │  .json     │
        └───────────────┘  └────────────┘
```

| Module | Responsibility |
| --- | --- |
| `models.py` | `RoomCategory`, `Room`, `Guest`, `Reservation`, `Payment`, `RoomOffer`; half-open date overlap logic; `Decimal` money. |
| `storage.py` | `JsonCollection` (atomic read/write), `RoomRepository`, `ReservationRepository`. |
| `payment.py` | `PaymentGateway`: simulated charge and refund, Luhn validation, deterministic declines. |
| `service.py` | `HotelService` facade: search, availability, quote, book, cancel, refunds, reports. |
| `seed.py` | Demo inventory across three categories. |
| `cli.py` | Interactive console front end; no business rules. |

### Room categories

| Category | Base rate / night | Max guests | Example amenities |
| --- | --- | --- | --- |
| Standard | $99 | 2 | Wi-Fi, TV, A/C, private bathroom |
| Deluxe | $159 | 3 | + king bed, mini bar, city view |
| Suite | $289 | 5 | + living room, bathtub, balcony, breakfast |

Rates live on the `RoomCategory` enum, so pricing has a single source of truth
and new categories only need one enum member.

### Booking and payment rules

1. Check-out must follow check-in; check-in cannot be in the past; at least one guest.
2. Party size must not exceed the category's capacity.
3. A room is available only if no **confirmed** reservation overlaps the stay.
   Overlap uses half-open intervals `[check-in, check-out)`, so a room may be
   rebooked on the day the previous guest checks out.
4. Price = `nightly_rate × nights`, plus 10% tax.
5. Payment is charged **before** the reservation is written. If a rival booking
   claims the room during processing (re-checked just before saving), the charge
   is refunded and the booking is rejected with `RoomUnavailable`.
6. Cancellation is free (100% refund) up to 2 days before check-in, 50% after
   that, and impossible once the stay has started. Refunds are recorded as a
   second `Payment` row linked to the reservation.
7. Only the last four digits of a card are ever stored.

### Simulated payment gateway

* Card payments are validated with the Luhn checksum; invalid numbers raise
  `InvalidCardError`.
* Any Luhn-valid card ending in `0000` is declined (`PaymentDeclined`).
* `4242 4242 4242 4242` (the CLI default) succeeds.
* `cash` records a "pay at the front desk" payment with no card data.

### Persistence format

`data/rooms.json` — list of rooms (`number`, `category`, `floor`, `amenities`, `active`).

`data/reservations.json` — list of reservations including cancelled history:

```json
{
  "id": "RSV-A02CE148",
  "room_number": "101",
  "guest": {"name": "Test Guest", "email": "test@example.com", "phone": "+1 555 0100"},
  "check_in": "2026-11-10",
  "check_out": "2026-11-12",
  "guests": 2,
  "nightly_rate": "99.00", "subtotal": "198.00", "tax": "19.80", "total": "217.80",
  "status": "CONFIRMED",
  "payment": {"transaction_id": "TXN-...", "amount": "217.80", "method": "cash",
               "status": "PAID", "card_last4": "", "message": "...", "created_at": "..."},
  "refund_payment": null,
  "refunded": "0.00",
  "created_at": "...", "cancelled_at": null
}
```

Writes go to a temp file in the same directory and are moved into place with
`os.replace`, so a crash cannot corrupt the store. Money is stored as strings
and parsed back into `Decimal` to avoid float drift. Availability is **derived**
from reservations rather than stored separately, which removes any chance of an
availability index disagreeing with the booking history.

## Programmatic use

```python
from hotel_booking import Guest, RoomCategory, HotelService
from hotel_booking.service import parse_date

hotel = HotelService("data")

offers = hotel.search(parse_date("2026-11-10"), parse_date("2026-11-12"),
                      guests=2, category=RoomCategory.DELUXE)
reservation = hotel.book(offers[0].room.number,
                         Guest("Ada Lovelace", "ada@example.com"),
                         parse_date("2026-11-10"), parse_date("2026-11-12"),
                         guests=2, card_number="4242424242424242")
print(reservation.total)
hotel.cancel(reservation.id)   # refund depends on the cancellation policy
```

## Tests

21 `unittest` cases cover search filters, capacity and date validation,
double-booking prevention, checkout-day turnover, decline handling, refund
rules, occupancy/revenue reporting, and persistence/reload (including corrupted
file handling):

```bash
python -m unittest discover -s tests -v
```

## Possible extensions

* Weekend/seasonal rate multipliers and promo codes (add to `RoomOffer.build`).
* Multiple rooms per reservation (a `Reservation` aggregates `RoomStay`s).
* A SQL repository implementing the same `RoomRepository` interface.
* A REST/HTTP layer over `HotelService`; the CLI is already just one client.
