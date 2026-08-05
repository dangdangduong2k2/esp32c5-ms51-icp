# MS51 application image

Place the raw APROM image at `firmware/ms51_app.bin`, then run `idf.py build`.
The image must start at MS51 APROM address `0x0000`; gaps must be filled with
`0xFF`. Intel HEX text is not accepted directly.

If this file is absent, the ESP32 project still builds and exposes the console,
but the `program` and `verify` commands report that no image is embedded.

The normal `program` command preserves APROM bytes after the end of this file.
Use `program-full CONFIRM` only when those trailing bytes should be erased.
