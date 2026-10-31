# Hẹn Giờ Lạnh 3.7.13

Bản Android 3.7.13 (versionCode 69) triển khai giao diện sáng theo concept đã duyệt.

- Nền sáng, thẻ trắng bo góc, màu xanh chủ đạo, tiêu đề và biểu tượng gọn hơn.
- Căn hai nhiệt độ, đường phân chia và các ngưỡng thẳng hàng. Giữ hai nhóm cảm biến/ngưỡng liên quan trong cùng thẻ.
- Căn giữa số LED; giữ nguyên giá trị, điều kiện nhấp nháy và hoạt động của mạch.
- Cài đặt nâng cao thu gọn được; giữ các nhóm con, đơn vị và quyền hiển thị hiện có.
- Thông báo tác vụ dạng viên nổi, có biểu tượng, nút đóng và chuyển động nhẹ; không lấy focus, không kéo trang hay chặn thao tác.
- Giữ trạng thái Bật/Tắt của đầu ra để người dùng không phải chỉ dựa vào màu.

Chỉ cần cài đè APK. Không cần nạp lại C5 hoặc MS51. Không thay đổi giao thức, máy chủ, ghép nối hay logic điều khiển.

## Tải

[APK 3.7.13](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.13.apk)

## Ảnh app thật

Ảnh chụp giao diện native trên Android emulator với dữ liệu minh họa offline; không phải ảnh AI. Ảnh concept là mục tiêu thị giác, không phải ảnh kiểm chứng. Font Android, vùng bấm tối thiểu và số mục được cấp quyền có thể làm bố cục khác đôi chút giữa điện thoại.

- [Tổng quan](native-overview.png)
- [Cài đặt](native-settings.png)
- [Thông báo](native-notice.png)

## Kiểm chứng

- `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest`: thành công, không có lỗi lint Error/Fatal.
- BLE, nearby, managed, notice, scroll, stepper, choice và ui-preview: PASS trên Android 35 emulator.
- Đã xem ảnh native ở 400 dp/font 1.0 và 360 dp/font 1.3. Với chữ lớn, các thẻ nhiệt độ chuyển thành một cột để giữ khả năng đọc.
- BLE kiểm tra transport/callback mô phỏng; chưa kiểm chứng lại radio trên điện thoại và hộp thật trong lần cập nhật giao diện này.
- APK giữ chữ ký cài đè hiện có; SHA-256 nằm trong `SHA256SUMS.txt`.
