# MS51 saved durations (3.1.1)

The actual application in `MS51_code/260502 add PIN/header.h` defines
`HIS_MAX 30` and `uint32_t sig_count[30]`. `main.c` increments `count_log`
every 100 ms during `hcf_mode_timeout`, then stores the duration at slot 0
when that interval ends. `display_history_data()` displays the selected
slot as whole minutes (`sig_count[list_data] / 600`) alongside L00–L29.
These are elapsed durations, not RTC timestamps. This release does not
change the controller's existing history insertion/wrap behavior.

The existing UART `G` command already returns all 217 bytes of `eData`.
Byte 0 must be layout version 2. History occupies bytes 0x33 through 0xAA:
30 unsigned, big-endian 32-bit values. Byte 0x32 (`sig_index`) wraps and
must not be treated as the number of readable entries. All 30 slots are
decoded. Zero slots are hidden without renumbering nonzero slots.

Authenticated `GET /devices/{device}/history` returns exactly:

```json
{"ok":true,"capacity":30,"tick_ms":100,"ticks":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
```

The handler verifies device ownership, reuses the existing MQTT runtime-config
read, respects active device operations, validates the configuration layout,
and returns only durations. It does not expose hidden settings or PIN bytes.
No EEPROM write, reset, ICP operation or firmware change is performed.
Hidden settings do not prevent reading the history.

Android polls history every 15 seconds while the overview is active, independently
handles history errors, labels retained history as stale, and clears it when
changing devices or accounts. Existing control/view epochs discard late replies.
Manual refresh also requests history. Local/demo paths decode the same byte range;
cloud uses the dedicated history endpoint. Firmware without the 217-byte UART
GET protocol reports unavailable history rather than fabricated values.

Validation: run `server/test_managed.py` directly with `MS51_TEST_WORK` set to
an isolated directory. The script builds its own 26-test suite (do not use
generic unittest discovery, which also inherits base tests with incompatible
fixture setup). Android instrumentation uses `-e mode managed`, with no live
programming commands. It checks unsigned values, all 30 slots including L29,
sub-minute precision, empty/stale/device-switch UI and removal of raw status logs.

Deployment was verified via public HTTPS/MQTT against the existing paired MS51.
It returned 30 zero slots at verification time, correctly represented as an
empty history. Account credentials and device ownership were unchanged. The
nonempty display cases use deterministic test data; no artificial records were
written into the physical controller.
