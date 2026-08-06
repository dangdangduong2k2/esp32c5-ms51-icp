#!/usr/bin/env python3
"""Convert a checked Intel HEX APROM image into a dense binary image."""

from __future__ import annotations

import argparse
from pathlib import Path


def parse_hex(path: Path) -> dict[int, int]:
    memory: dict[int, int] = {}
    base = 0
    saw_eof = False

    for line_number, raw_line in enumerate(path.read_text(encoding="ascii").splitlines(), 1):
        line = raw_line.strip()
        if not line:
            continue
        if saw_eof or not line.startswith(":") or len(line) < 11 or len(line[1:]) % 2:
            raise ValueError(f"line {line_number}: invalid Intel HEX record")
        try:
            record = bytes.fromhex(line[1:])
        except ValueError as error:
            raise ValueError(f"line {line_number}: invalid hexadecimal data") from error
        count = record[0]
        if len(record) != count + 5 or sum(record) & 0xFF:
            raise ValueError(f"line {line_number}: invalid record length or checksum")

        offset = (record[1] << 8) | record[2]
        record_type = record[3]
        payload = record[4:-1]
        if record_type == 0x00:
            address = base + offset
            for index, value in enumerate(payload):
                target = address + index
                if target in memory:
                    raise ValueError(f"line {line_number}: overlapping address 0x{target:04X}")
                memory[target] = value
        elif record_type == 0x01:
            if count != 0 or offset != 0:
                raise ValueError(f"line {line_number}: invalid EOF record")
            saw_eof = True
        elif record_type == 0x02:
            if count != 2 or offset != 0:
                raise ValueError(f"line {line_number}: invalid segment-address record")
            base = ((payload[0] << 8) | payload[1]) << 4
        elif record_type == 0x04:
            if count != 2 or offset != 0:
                raise ValueError(f"line {line_number}: invalid linear-address record")
            base = ((payload[0] << 8) | payload[1]) << 16
        elif record_type in (0x03, 0x05):
            if count != 4 or offset != 0:
                raise ValueError(f"line {line_number}: invalid start-address record")
        else:
            raise ValueError(f"line {line_number}: unsupported record type {record_type:02X}")

    if not saw_eof or not memory:
        raise ValueError("missing EOF record or no data records")
    return memory


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Intel HEX input")
    parser.add_argument("output", type=Path, help="dense binary output")
    parser.add_argument("--max-size", type=lambda value: int(value, 0), default=0x8000)
    args = parser.parse_args()

    memory = parse_hex(args.input)
    maximum = max(memory)
    if maximum >= args.max_size:
        raise SystemExit(f"image reaches 0x{maximum:04X}, beyond 0x{args.max_size - 1:04X}")
    image = bytearray([0xFF]) * (maximum + 1)
    for address, value in memory.items():
        image[address] = value
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(image)
    print(f"{args.output}: {len(memory)} covered byte(s), range 0x0000..0x{maximum:04X}")


if __name__ == "__main__":
    main()
