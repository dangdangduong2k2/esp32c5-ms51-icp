# Hẹn Giờ Lạnh 3.7.4 — BLE theo menu được phép trên mạch

[APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.4.apk) · [C5](https://103-9-78-139.sslip.io/downloads/ESP32-C5-Bluetooth-3.7.4.zip) · [MS51](https://103-9-78-139.sslip.io/downloads/MS51-Bluetooth-3.7.4.hex)

BLE hiển thị theo các mục đang được bật/cho phép trong cấu hình MS51, không theo quyền tài khoản hoặc danh sách các mode luôn mở. Cờ cho phép hiện menu khác với giá trị bật/tắt của chức năng: ví dụ DF đang Tắt nhưng menu DF được phép thì vẫn phải hiện để người dùng bật lại.

| Mục | Điều kiện trên MS51 |
|---|---|
| Làm lạnh CL/OP, Xả đá CL/OP, Bắt đầu làm lạnh/xả đá | Các tham số vận hành cơ bản của schema 2 |
| DF | Cờ hiện DF `0x27 = 1` |
| END | Cờ hiện END `0x28 = 1` |
| Kiểu màn hình | Cờ hiện SL `0x29 = 1` |
| HCF | Cờ hiện HCF `0x2A = 1` |
| HDF | Cờ hiện HDF `0x2B = 1`; không còn luôn mở |
| ST LED | ST được phép `0xC3 = 1` và mạch dùng LED hoặc cho đổi kiểu màn hình |
| ST LCD | ST được phép `0xC3 = 1` và mạch dùng LCD hoặc cho đổi kiểu màn hình |

Không tự mở các trường quản trị, cờ cấp quyền menu, mật khẩu, giới hạn Min/Max hoặc toàn bộ cấu hình thô. Chế độ online vẫn theo tài khoản như trước. Hai ngưỡng nhiệt và cập nhật HEX không được tự mở chỉ vì kết nối BLE.

App lấy giao của danh sách thiết bị cung cấp và các cờ menu, nên cũng sửa được hiển thị khi dùng C5 3.7.0-r2. Không có danh sách từ mạch thì không tự mở tất cả. Mỗi lần vào/đọc Cài đặt hoặc lưu xong đều áp dụng cờ hiện tại. Trước khi gửi lệnh Lưu, app đọc lại cài đặt và từ chối cả yêu cầu nếu có trường vừa bị ẩn; không tự phát lại lệnh ghi.

C5 kiểm tra lại trên cấu hình mới đọc cho cả GET và POST, trả `policy_source=controller_menu`. MS51 mới báo `local_settings=2`, tự kiểm tra cùng các điều kiện và từ chối nguyên lệnh nếu client sửa một mục hiện đã bị ẩn. C5 mới chấp nhận protocol lưu cục bộ 1 và 2; không dùng lệnh ghi cấu hình thô để thay thế. Mật khẩu, bộ đếm bảo vệ, cờ menu và Min/Max vẫn giữ nguyên khi lưu vận hành.

## Cập nhật

- Cài đè APK cùng chữ ký, code 60. Phần hiển thị và kiểm tra trước lưu có hiệu lực với C5 r2 đang dùng.
- Đã xuất C5 và MS51; **chưa nạp lên bo trong lần này**. Để có kiểm tra quyền đồng bộ ở cả ba lớp, nạp C5 mới trước rồi MS51 mới. C5 cũ chỉ nhận `local_settings=1`, nên sẽ khóa lưu offline nếu nạp MS51 mới trước.
- Gói C5 dùng profile RAM r2, flash 16 MB; DAT IO1 / CLK IO0 / RS IO3; NTC 10k IO5, 20k IO6, 50k IO4.
- Ba file nạp: `bootloader.bin` tại `0x2000`, `partition-table.bin` tại `0x8000`, `esp32c5_ms51_icp.bin` tại `0x10000`. Không Erase All; bố cục NVS giữ nguyên.
- HEX MS51 không ghi trang EEPROM `0x7F00–0x7FFF`; không dùng thao tác xóa toàn bộ mạch.

## Kiểm thử

Build Android và lint thành công; Android 35 giả lập chạy qua `ble`, `nearby`, `managed`. App, test C5 và test từ hàm MS51 thật cùng kiểm tra 128 tổ hợp (sáu cờ menu × LCD/LED), gồm cờ tắt nhưng giá trị chức năng đang bật, thiếu danh sách quyền và từ chối lưu khi quyền thay đổi.

C5 build ESP-IDF 6.0 thành công, MS51 Keil 0 lỗi/0 cảnh báo; MS51 29.159 byte. Test MS51 giữ mật khẩu/bộ đếm, khóa PIN, Min/Max và từ chối nguyên lệnh. C5 giữ dữ liệu riêng tư, không dùng lệnh ghi cũ, từ chối quyền không hợp lệ/capability hết hạn. Chưa xác minh bản C5/MS51 mới qua radio hoặc nạp thực tế.
