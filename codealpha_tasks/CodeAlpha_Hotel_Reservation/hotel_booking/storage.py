"""File-based persistence.

Two JSON documents hold all mutable state:

* ``rooms.json``        - the room inventory
* ``reservations.json`` - every reservation, including cancelled ones (history)

Writes are atomic (write to a temp file in the same directory, then
``os.replace``) so an interrupted process cannot leave a half-written file.
Availability is never persisted; it is derived from the reservation history,
which keeps the two files from drifting out of sync.
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any, Callable, Iterable, TypeVar

from .models import Reservation, Room

T = TypeVar("T")


class StorageError(RuntimeError):
    pass


class JsonCollection:
    """A tiny JSON-document collection with atomic save."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def load(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                data = json.load(handle)
        except json.JSONDecodeError as exc:
            raise StorageError(f"Corrupted data file {self.path}: {exc}") from exc
        if not isinstance(data, list):
            raise StorageError(f"Expected a JSON list in {self.path}")
        return data

    def save(self, records: Iterable[dict[str, Any]]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(list(records), indent=2, ensure_ascii=False)
        fd, tmp_name = tempfile.mkstemp(
            dir=self.path.parent, prefix=self.path.name, suffix=".tmp"
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_name, self.path)
        except BaseException:
            if os.path.exists(tmp_name):
                os.unlink(tmp_name)
            raise


class RoomRepository:
    def __init__(self, collection: JsonCollection) -> None:
        self._collection = collection
        self._rooms: dict[str, Room] = {}
        for record in collection.load():
            room = Room.from_dict(record)
            self._rooms[room.number] = room

    def all(self) -> list[Room]:
        return sorted(self._rooms.values(), key=lambda r: (r.floor, r.number))

    def active(self) -> list[Room]:
        return [room for room in self.all() if room.active]

    def get(self, number: str) -> Room | None:
        return self._rooms.get(str(number))

    def add(self, room: Room) -> None:
        if room.number in self._rooms:
            raise StorageError(f"Room {room.number} already exists")
        self._rooms[room.number] = room
        self._flush()

    def set_active(self, number: str, active: bool) -> Room:
        room = self.get(number)
        if room is None:
            raise KeyError(f"Unknown room {number}")
        updated = Room(
            number=room.number,
            category=room.category,
            floor=room.floor,
            amenities=room.amenities,
            active=active,
        )
        self._rooms[number] = updated
        self._flush()
        return updated

    def _flush(self) -> None:
        self._collection.save(room.to_dict() for room in self.all())

    def seed_if_empty(self, rooms: Iterable[Room]) -> bool:
        if self._rooms:
            return False
        for room in rooms:
            self._rooms[room.number] = room
        self._flush()
        return True


class ReservationRepository:
    def __init__(self, collection: JsonCollection) -> None:
        self._collection = collection
        self._reservations: dict[str, Reservation] = {}
        for record in collection.load():
            reservation = Reservation.from_dict(record)
            self._reservations[reservation.id] = reservation

    def all(self) -> list[Reservation]:
        return sorted(self._reservations.values(), key=lambda r: r.created_at)

    def get(self, reservation_id: str) -> Reservation | None:
        return self._reservations.get(str(reservation_id).strip().upper())

    def add(self, reservation: Reservation) -> None:
        if reservation.id in self._reservations:
            raise StorageError(f"Duplicate reservation id {reservation.id}")
        self._reservations[reservation.id] = reservation
        self._flush()

    def update(self, reservation: Reservation) -> None:
        if reservation.id not in self._reservations:
            raise StorageError(f"Unknown reservation {reservation.id}")
        self._reservations[reservation.id] = reservation
        self._flush()

    def for_room(self, room_number: str) -> list[Reservation]:
        return [r for r in self.all() if r.room_number == str(room_number)]

    def for_email(self, email: str) -> list[Reservation]:
        target = email.strip().lower()
        return [r for r in self.all() if r.guest.email.lower() == target]

    def _flush(self) -> None:
        self._collection.save(r.to_dict() for r in self.all())
