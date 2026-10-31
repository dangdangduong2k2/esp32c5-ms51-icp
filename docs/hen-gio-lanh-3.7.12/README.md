# Hẹn Giờ Lạnh 3.7.12

Bản cập nhật Android 3.7.12 (versionCode 68).

- Tách kết nối Bluetooth khỏi các yêu cầu Internet đang chờ; hủy kết nối dở khi rời màn, tránh chặn lần kết nối tiếp theo.
- Chặn callback ghép nối/dò dịch vụ trùng; tiếp tục đăng ký dữ liệu nếu Android không trả callback MTU sau 2 giây.
- Bỏ chú thích thừa, mô tả chu trình và tên thiết bị phụ lặp lại trên các trang và popup. Giữ tên để chọn đúng hộp khi có nhiều thiết bị.
- Thông báo tác vụ chuyển sang một popup nổi có nút đóng và tự ẩn; nội dung cập nhật trong cùng popup, không đẩy trang hay giành focus ô nhập.
- Giữ trạng thái kết nối, lỗi cảm biến, dữ liệu, nhãn cài đặt và các hộp xác nhận cần thiết.

Chỉ cần cài đè APK, không cần nạp lại C5 hoặc MS51.

Kiểm tra: assembleDebug, lintDebug, assembleDebugAndroidTest thành công; BLE, nearby, managed, notice, scroll và preview đều PASS trên Android 35 emulator với Wi-Fi/data tắt. Đã xem ảnh giao diện native. BLE dùng mô phỏng transport/callback, chưa xác minh lại trên điện thoại và hộp thật.

## Tải và cài

[APK 3.7.12](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.12.apk). Cài đè bằng chữ ký hiện có; giữ dữ liệu ứng dụng.

## Ghi chú triển khai

`bleConnectIo` độc lập với `presenceIo`; chuyển offline xóa cờ mở thiết bị qua Internet còn chờ. Future kết nối có thể hủy; callback dùng setup epoch để bỏ kết quả cũ. `BleSetupState` chỉ cho từng bước bond/discovery/MTU/CCCD chạy một lần. Kết nối chỉ sẵn sàng sau khi CCCD được xác nhận. Không tự gửi lại các lệnh ghi.

`InAppNotice` là một PopupWindow không lấy focus, cập nhật tại chỗ, có nút đóng 48 dp và thời gian đọc theo cài đặt accessibility. Đóng khi app tạm dừng/hủy, không hiển thị lại thông báo cũ trong nội dung trang. Lỗi và trạng thái của dữ liệu/cảm biến vẫn được thể hiện tại vị trí liên quan.

## Kiểm chứng

- BLE: khối xử lý Internet bị chặn không ngăn tác vụ BLE; hủy kết nối; thứ tự bond/service/MTU, callback lặp và fallback; framing, identity, lưu cài đặt/đọc lại, ngưỡng, cảm biến lỗi và C5 cũ.
- Nearby: ngưỡng RSSI, hộp đã ghép, nhiều hộp, unpair, vòng đời, offline và tài khoản.
- Managed: cài đặt, ngưỡng, bộ đếm, chế độ, lịch sử và quyền.
- Notice: popup nổi thay thế tại chỗ, không nằm trong page, giữ layout/scroll/caret/focus, đóng và chặn callback nền.
- Scroll: cập nhật nền và xác nhận lưu không kéo trang; giữ bản nháp.
- Preview: ảnh native bằng fixture offline; không gửi lệnh tới hộp thật.

Cần thử lại tình huống gốc trên điện thoại: tắt Wi-Fi/data, vào Chế độ offline, chọn hộp đã ghép và kiểm tra vào Tổng quan ngay lần đầu. Không kết luận đã xác nhận lỗi radio trên phần cứng chỉ từ emulator.
