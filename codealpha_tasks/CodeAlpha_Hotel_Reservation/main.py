#!/usr/bin/env python3
"""Entry point: python main.py [--data-dir DIR]"""

from __future__ import annotations

import argparse

from hotel_booking.cli import main


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Hotel reservation desk")
    parser.add_argument(
        "--data-dir",
        default=None,
        help="Directory holding rooms.json and reservations.json (default: ./data)",
    )
    arguments = parser.parse_args()
    main(arguments.data_dir)
