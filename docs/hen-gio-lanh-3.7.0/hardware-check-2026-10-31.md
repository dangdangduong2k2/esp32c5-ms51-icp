# Kiểm tra C5 r2 trên bo — 31/10/2026

Người dùng báo BLE kết nối rồi tự ngắt với app 3.7.1. Bo vẫn dùng bản C5 trước khi xuất r2, RAM trống đọc qua USB là 20.400 byte, khối lớn nhất 9.216 byte.

Sau khi người dùng cho phép dừng mạch, đã sao lưu flash và nạp đúng gói `ESP32-C5-Bluetooth-3.7.0-r2.zip` đã phát hành, SHA-256 `1b1eba3b57854e361c77b2d78ae067febc1e5eda500c4b2e00ccd60e187e7fa7`.

- Xác minh bootloader, bảng phân vùng và app thành công; vùng NVS trước/sau giống từng byte.
- Không nạp hoặc đổi cấu hình MS51.
- Cần người dùng bấm RESET/EN sau lần nạp qua JTAG.
- Sau khởi động: RAM trống 73.564 byte, khối lớn nhất 51.200 byte.
- Người dùng kết nối lại bằng điện thoại và báo “Đã kết nối, hiện vẫn ổn”.
- Theo dõi USB 45 giây: 186 dòng liên quan indication; ba mẫu RAM trống 67.716 / 67.704 / 60.580 byte, khối lớn nhất 25.600 byte. Không thấy panic/reset hoặc lỗi cấp phát trong đoạn log này.
- Đọc `/api/status` qua LAN trực tiếp thành công. Công cụ chẩn đoán LAN cần bỏ proxy HTTP của máy tính để tránh kết quả lỗi định tuyến không liên quan tới C5.

Đây là xác nhận kết nối và quan sát ngắn hạn, không thay thế kiểm thử chạy dài, ghép lại nhiều lần, lưu offline/mở khóa, thêm Wi-Fi/tài khoản hoặc tải HEX đồng thời. Giữ app 3.7.1; không phát hành APK mới cho lần nạp này. Log thô và bản sao flash có thể chứa dữ liệu riêng nên chỉ lưu cục bộ, không đưa lên Git.
