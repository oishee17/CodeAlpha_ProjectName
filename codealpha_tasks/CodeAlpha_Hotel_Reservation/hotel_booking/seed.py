"""Demo room inventory used the first time the system starts."""

from __future__ import annotations

from .models import Room, RoomCategory

STANDARD_AMENITIES = ("Wi-Fi", "TV", "Air conditioning", "Private bathroom")
DELUXE_AMENITIES = STANDARD_AMENITIES + ("King bed", "Mini bar", "City view")
SUITE_AMENITIES = DELUXE_AMENITIES + ("Living room", "Bathtub", "Balcony", "Breakfast")


def default_rooms() -> list[Room]:
    return [
        # Floor 1 - Standard
        Room("101", RoomCategory.STANDARD, 1, STANDARD_AMENITIES),
        Room("102", RoomCategory.STANDARD, 1, STANDARD_AMENITIES),
        Room("103", RoomCategory.STANDARD, 1, STANDARD_AMENITIES),
        Room("104", RoomCategory.STANDARD, 1, STANDARD_AMENITIES),
        # Floor 2 - Deluxe
        Room("201", RoomCategory.DELUXE, 2, DELUXE_AMENITIES),
        Room("202", RoomCategory.DELUXE, 2, DELUXE_AMENITIES),
        Room("203", RoomCategory.DELUXE, 2, DELUXE_AMENITIES),
        # Floor 3 - Deluxe and the first Suite
        Room("301", RoomCategory.DELUXE, 3, DELUXE_AMENITIES),
        Room("302", RoomCategory.SUITE, 3, SUITE_AMENITIES),
        # Floor 4 - Suites
        Room("401", RoomCategory.SUITE, 4, SUITE_AMENITIES),
        Room("402", RoomCategory.SUITE, 4, SUITE_AMENITIES),
    ]
