# Hẹn Giờ Lạnh 3.7.7 — Nhiệt độ và hai ngưỡng qua Bluetooth

[Tải app](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.7.apk) · [Tải C5](https://103-9-78-139.sslip.io/downloads/ESP32-C5-Bluetooth-3.7.7.zip)

BLE hiển thị nhiệt độ động cơ (đổi 10k/20k), nhiệt độ giám sát 50k, ngưỡng an toàn và ngưỡng xả đá. Cả hai ngưỡng có bật/tắt, mũi tên tăng/giảm và lưu trực tiếp vào C5, không cần tài khoản hoặc máy chủ. Logic điều khiển giữ nguyên: vượt ngưỡng động cơ kết thúc OP1; 50k dưới ngưỡng bật relay xả đá, lỗi cảm biến trả về điều khiển theo mạch.

Cần cài app 3.7.7 (versionCode 63) và cập nhật C5 trong gói. Không cần nạp lại MS51 3.7.4 đang dùng. C5 cũ vẫn cho xem nhiệt độ, khóa lưu ngưỡng và báo cần cập nhật. Bản này chưa được nạp lên bo thực.

Nhiệt độ được hiển thị ngay sau khi đọc C5, không chờ lịch sử hoặc dữ liệu MS51. Cảm biến lỗi hoặc dữ liệu quá hạn không hiển thị thành nhiệt độ hợp lệ. Các giới hạn menu MS51 và ghép nối BLE được giữ nguyên; không mở đường ghi cấu hình thô hay nạp chương trình qua BLE.

## Nạp C5

Flash 16 MB, DIO, 80 MHz; không Erase All. Giữ vùng NVS/Wi-Fi/ghép nối. Nạp bootloader.bin tại 0x2000, partition-table.bin tại 0x8000, esp32c5_ms51_icp.bin tại 0x10000. DAT IO1, CLK IO0, RS IO3; NTC 10k IO5, 20k IO6, 50k IO4. Cấu hình chân, máy chủ và bảng phân vùng được đối chiếu bằng nhau với bản đang dùng.

## Kiểm tra

- Build C5, Android APK, test APK và lint thành công; chữ ký APK khớp 3.7.6.
- Native BLE policy: giữ dữ liệu ba cảm biến, nhận đúng năm trường cấu hình nhiệt, từ chối các route ngoài phạm vi.
- Android 35 giả lập: BLE, managed và nearby đều PASS. BLE kiểm tra đổi cảm biến, lưu/đọc lại, bật/tắt riêng, số âm/thập phân, giới hạn, lưu thất bại, C5 cũ và cảm biến lỗi/quá hạn.
- Kiểm thử logic OP1, relay 3 và chuyển đổi NTC PASS. Kiểm thử nhiệt độ và ghi BLE dùng dữ liệu/transport giả lập; chưa kiểm thử 3.7.7 trên điện thoại và bo thật.
- APK/ZIP đã tải lên VPS và tải lại HTTPS, đối chiếu SHA-256.
