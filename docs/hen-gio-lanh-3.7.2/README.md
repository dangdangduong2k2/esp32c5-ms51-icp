# Hẹn Giờ Lạnh 3.7.2 — Tự kết nối hộp đã ghép

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.2.apk)

Khi mở app ở trang đầu, thiết bị đã được app xác minh và vẫn còn ghép Bluetooth trên điện thoại sẽ tự kết nối khi được quét thấy. Popup giữ hình hộp xoay, hiện “Đang kết nối…” rồi tự đóng và mở Tổng quan khi đọc được trạng thái đúng thiết bị. Không cần nhấn Kết nối thêm lần nữa.

- Hộp mới vẫn cần chạm chọn và xác nhận ghép Bluetooth của Android nếu được hỏi.
- Nếu đã dùng nhiều hộp, tự nối lại hộp kết nối thành công gần nhất. Các hộp khác vẫn có thể chọn thủ công.
- Nâng từ 3.7.1 với một hộp đã lưu: tự nhận ngay. Nếu có nhiều hộp cũ nhưng chưa lưu hộp gần nhất, chọn một lần để tránh tự mở nhầm hộp.
- Đóng popup sẽ hủy việc mở Tổng quan; phản hồi muộn không được đổi màn hình. Lỗi kết nối có nút thử lại, không tự lặp kết nối liên tục.
- Quét chỉ chạy khi app ở phía trước và chưa điều khiển thiết bị. Bluetooth/ quyền Thiết bị ở gần vẫn theo yêu cầu của Android.

Chỉ cài đè APK, không nạp lại C5 hoặc MS51. Dùng C5 r2 đã nạp trước đó. Không đổi cài đặt thiết bị, quyền offline/online hoặc gửi lại lệnh điều khiển.

## Kiểm tra

Build Android, lint và các bài kiểm tra `nearby`, `ble`, `managed` trên Android 35 giả lập. Bài kiểm tra xác minh chính sách nhớ thiết bị, ghép nối đã bị xóa, nâng cấp 3.7.1, nhiều hộp, quảng bá lặp chỉ bắt đầu một kết nối, popup có tiến trình/hủy và tự đóng khi vào Tổng quan. Giữ kiểm tra GIF, dữ liệu BLE, quyền offline và lưu các giá trị đã đổi.

Giả lập kiểm tra luồng ứng dụng với sự kiện quét/dữ liệu mô phỏng; việc tự nối với radio C5 thực tế cần kiểm tra trên điện thoại sau khi cài bản này.
