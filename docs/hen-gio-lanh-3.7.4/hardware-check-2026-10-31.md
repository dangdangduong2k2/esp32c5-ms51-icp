# Nạp và xác minh 3.7.4 trên bo — 31/10/2026

Người dùng yêu cầu nạp ngay. Đã nạp C5 trước rồi MS51, trên thiết bị `ms51-3844be1fe4e0`.

- C5 dùng đúng gói phát hành `ESP32-C5-Bluetooth-3.7.4.zip`, SHA-256 `b39550861de57d1565149743065c1447dd6fc5512d30ec4ad7cc80662ccf4beb`.
- Sao lưu flash trước khi ghi; xác minh cả bootloader, bảng phân vùng và ứng dụng thành công. Vùng NVS `0x9000..0xEFFF` trước/sau giống từng byte. Không xóa toàn bộ flash.
- Người dùng bấm RESET/EN sau khi nạp qua JTAG. C5 trở lại mạng, đọc được trạng thái và cấu hình MS51.
- MS51 dùng `MS51-Bluetooth-3.7.4.hex`, SHA-256 `e33b07e273a48d827f9cfca6eb62c5550f226d7889610752264cfbe38ef4f962`.
- Dùng lệnh program thường, giữ phần APROM ngoài ảnh chương trình; không dùng program-full. Sau khi ghi đã chạy verify riêng, kết quả firmware trên MS51 khớp file tải lên.
- Cấu hình runtime trước/sau không có byte nào thay đổi. Telemetry xác nhận `local_settings=2`.

Script nạp bị lỗi mã hóa console khi in thông báo tiếng Việt sau khi ghi thành công; đã tiếp tục từ bước verify, không nạp lặp. Log, bản sao flash và cấu hình thô lưu riêng tại máy, không đưa lên Git.

Chưa kiểm thử lại phiên BLE bằng điện thoại sau lần nạp này; kiểm tra trên bo lần này xác nhận ảnh chương trình, cấu hình và telemetry. Không thay đổi mode, relay hay tham số vận hành để thử.
