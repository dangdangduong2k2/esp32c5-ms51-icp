# MS51 Connect 3.4.10

- Move Thời gian chờ ST, ST LED and ST LCD into Advanced Settings. Timing now contains only the four cooling/defrost CL/OP fields.
- Remove Hiện ST LED and Xả đá OP tính giây from the mobile UI and editable field policy. Older mobile clients are also denied writes to those two keys by the server.
- Add an admin-only device form under Cấp quyền → Tùy chọn trên mạch: read MCU values, edit two options and explicitly save to the MCU.
- Admin saves use fresh configuration, a per-device lease, option revision checks and acknowledgement. Other settings are preserved except existing SET PIN-runtime normalization. MCU restart/counter reset behavior remains unchanged. No-op saves do not restart the MCU.
- Deploy server managed.py and admin_web files together. Existing permission choices are preserved; revisions increase where retired fields were removed.
- No C5 or MS51 firmware update required.

Validation: see test-results.json. The server tests mock hardware. No live device setting was changed for validation.
