# ThuầnPhongvlog 3.7.17 — cảm biến động cơ và giám sát

- Tên app giảm từ 22 xuống 20 sp; dải tự co chữ giảm từ 18–22 xuống 16–20 sp.
- **Nhiệt độ động cơ** ở ô trái dùng cố định **50k, GPIO4**. Ngưỡng an toàn
  lấy cảm biến này: vượt ngưỡng thì hoàn thành OP1 theo cơ chế hiện có.
- **Nhiệt độ giám sát** ở ô phải cho đổi giữa **10k GPIO5** và **20k GPIO6**.
  Ngưỡng xả đá lấy đúng cảm biến đang chọn; thấp hơn ngưỡng thì bật đầu ra
  xả đá. Khi cảm biến giám sát lỗi/mất dữ liệu, đầu ra trở về điều khiển mạch.
- Chế độ offline hiện biểu tượng Bluetooth và chữ Bluetooth, không dùng lại
  biểu tượng hay tên Wi-Fi của hộp. Chế độ online vẫn hiển thị Wi-Fi và RSSI.

## Cài đặt

- [APK 3.7.17](https://103-9-78-139.sslip.io/downloads/ThuanPhongvlog-3.7.17.apk)
  giữ applicationId và chữ ký cũ, versionCode 73, cài đè được.
- [Gói C5 chuẩn](https://103-9-78-139.sslip.io/downloads/ESP32-C5-STANDARD-SENSORS-R5.zip)
  cần nạp để đổi nguồn điều khiển hai ngưỡng. C5 cũ chỉ cho xem nhiệt độ trong
  app mới; lưu ngưỡng bị khóa và có thông báo cập nhật hộp, tránh gửi sai nguồn.
- **Chỉ xuất file trong tác vụ này, chưa nạp mạch theo yêu cầu người dùng.**
- MS51 hiện tại (3.7.6) dùng tiếp được: C5 tính nhiệt độ rồi gửi các trạng thái
  an toàn/xả đá qua giao thức sẵn có. Không cần đổi mã MS51 cho việc đổi nguồn.
  File MS51 3.7.6 hiện có được kèm lại để dùng khi cần cho bo còn bản cũ.

## Nạp C5

Phần cứng chuẩn, flash cấu hình **4 MB**, cũng chạy trên C5 có flash vật lý lớn
hơn. DAT GPIO1, CLK GPIO0, RS GPIO3; NTC 10k GPIO5, 20k GPIO6, 50k GPIO4.
Không thay sơ đồ chân hoặc thông số điện trở/NTC. BLE và máy chủ đều được giữ.

Trong esptool-js chọn đủ ba file:

| Địa chỉ | File |
| --- | --- |
| `0x2000` | `bootloader.bin` |
| `0x8000` | `partition-table.bin` |
| `0x10000` | `ESP32-C5-STANDARD-SENSORS-R5-app.bin` |

Không chọn **Erase All** để giữ Wi-Fi, ghép nối và cấu hình. Sau khi nạp, RESET/EN
nếu bo chưa tự chạy. Gói có bảng địa chỉ, sdkconfig, manifest và checksum.

## Giao thức và lưu cấu hình

`/api/thermal` mới bắt buộc `roles_version: 2`. `source` chọn cảm biến giám sát
`10K`/`20K`; nguồn động cơ cố định `50K` và không thể đổi qua yêu cầu này.
Status trả rõ `roles_version`, `motor_source`, `monitor_source`. Máy chủ vẫn
chuyển tiếp lệnh cũ cho bo cũ; C5 R5 từ chối lệnh thiếu phiên bản vai trò mới.

Cấu hình NVS mới dùng magic SIG5. Khi đọc cấu hình trước đó, C5 giữ lựa chọn
10k/20k, các số ngưỡng và công tắc; sau nâng cấp, các ngưỡng áp dụng theo nguồn
mới kể trên. Mất mẫu cảm biến động cơ không tự kích ngưỡng an toàn; lỗi cảm biến
giám sát không chặn ngưỡng an toàn. SIG vẫn lấy chân vật lý.

## Kiểm chứng

- Host test chạy mã C5 thật: nguồn an toàn luôn 50k, giám sát theo 10k/20k,
  công tắc độc lập, bằng/vượt/dưới ngưỡng, cảm biến lỗi, lưu NVS, reboot,
  tương thích MS51 cũ, thời hạn heartbeat và dung lượng JSON.
- Test mã MS51 hiện hữu: OP1/OP2, HCF, SIG vật lý, relay 3 trở về theo mạch,
  hết heartbeat, rollover và NTC: PASS. MS51 không đổi source trong tác vụ này.
- Server managed regression 45 tests: PASS, gồm phiên bản vai trò hợp lệ/sai.
- Android build/lint đạt; managed/BLE và preview native đạt với dữ liệu giả lập.
  Preview 375 dp/font 1.0 và 360 dp/font 1.3; không gửi lệnh tới hộp thật.
- Chưa xác minh radio/relay trên phần cứng chạy R5 vì người dùng yêu cầu nạp sau.

[Ảnh online](overview-375dp.png) · [Ảnh offline](overview-ble-375dp.png)
