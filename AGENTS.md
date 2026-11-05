# User preferences

- Store generated files, downloads, intermediate work, and deliverables on drive D:.
  Default task root: `D:/Codex/Documents`, with `work/` and `outputs/` directories.
- From 2026-11-05, work only on the standard hardware firmware unless the user
  explicitly requests another variant: DAT GPIO1, CLK GPIO0, reset GPIO3;
  NTC 10k GPIO5, NTC 20k GPIO6, NTC 50k GPIO4.
- Do not edit, build, package, or flash the old IO1/N4 test hardware variants.
  Existing software unit tests remain applicable.
- Standard firmware packages must state their flash configuration, include exact
  flash addresses, and preserve Wi-Fi/pairing data by avoiding Erase All.
- Use the standard 4 MB flash configuration for new packages. The partition map
  fits within 4 MB and also runs on C5 devices with larger physical flash.
