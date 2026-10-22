# Admin access dialog automatic reads

Opening Cấp quyền loads the saved server permission policy and current MCU options in parallel. Existing permission checkboxes reflect the saved policy. Hiện ST LED and Xả đá OP tính giây now read automatically rather than requiring a separate click.

The dialog shows separate load status for each source. MCU failures leave both options unknown/indeterminate with Save disabled; saved server permissions remain usable after the read completes. A failed policy load remains unsaveable even after another action unlocks controls. The dialog can be closed during reads, and epoch checks ignore responses from closed dialogs or other devices. No writes occur on open. The existing manual read action remains available for retry.

Validation: headless Edge using mocked responses passed saved checkbox selection, automatic GET, offline state, policy failure, retry, closing while loading, late response isolation and zero writes. Production static files were verified byte-for-byte over HTTPS; health/API and MQTT remained healthy. No real device option write was performed. No server restart or APK/firmware update required.
