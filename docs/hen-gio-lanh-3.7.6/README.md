# Hẹn Giờ Lạnh 3.7.6 — Tự dò tại trang đăng nhập

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.6.apk)

Trang Đăng nhập tự dò Bluetooth và hiện popup GIF khi tìm thấy hộp, kể cả điện thoại còn danh sách thiết bị Wi-Fi đã ghép. Bỏ nút Dò thiết bị tại trang này. Người dùng vẫn có thể đóng popup; app vẫn cần quyền Bluetooth và Bluetooth được bật.

Sau đăng nhập, trang danh sách đã có thiết bị Wi-Fi tiếp tục yêu cầu bấm Dò thiết bị như bản 3.7.5. Tài khoản chưa có danh sách được xác nhận không tự coi là trống; tài khoản đã xác nhận không có thiết bị vẫn tự dò. Các kiểm tra quyền theo mạch, kết nối lại BLE và chọn nhiều thiết bị giữ nguyên.

APK versionCode 62, cài đè bản trước. Chỉ thay đổi app, không cần nạp lại C5/MS51.

## Kiểm tra

Build, lint và test APK thành công. Android 35 giả lập chạy `nearby` và `ble` đều PASS; scanner/lifecycle được kiểm tra với Bluetooth bật. Bổ sung trường hợp trang Đăng nhập có thiết bị Wi-Fi lưu sẵn, không có nút dò, tự quét và chỉ hiện popup sau advertisement; cả trường hợp phiên đăng nhập hết hạn còn account ID cũ. Tiếp tục kiểm tra trang danh sách có thiết bị vẫn không tự quét.

Advertisement trong kiểm thử được giả lập; chưa thử bản app này trên điện thoại thật. Không thay đổi hay gửi lệnh điều khiển tới bo trong lần kiểm tra này.
