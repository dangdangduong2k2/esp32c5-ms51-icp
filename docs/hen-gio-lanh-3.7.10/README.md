# Hẹn Giờ Lạnh 3.7.10 — Dò thiết bị mới và offline tại trang thiết bị

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.10.apk)

Trang danh sách sau đăng nhập có hai lựa chọn: Dò thiết bị mới (nút chính) và Chế độ offline (ô nhỏ). Cả hai mở màn hướng dẫn GIF đưa điện thoại lại gần hộp và bắt đầu dò. Dò thiết bị mới yêu cầu chọn hộp rõ ràng; Chế độ offline giữ khả năng tự kết nối lại hộp đã ghép. Hộp chưa ghép vẫn cần RSSI mạnh, ổn định như 3.7.8.

Nút Quay lại thiết bị và Back trả về danh sách, giữ phiên tài khoản, dừng scanner/popup. Nếu bắt đầu từ đăng nhập, vẫn quay lại đăng nhập. Danh sách có hoặc chưa có thiết bị đều không tự dò/popup; chỉ bắt đầu khi người dùng chọn chức năng. Luồng bỏ ghép Bluetooth cũng dùng màn hướng dẫn chung.

Chỉ cập nhật APK versionCode 66, cài đè cùng chữ ký; không nạp lại C5/MS51, không sửa cấu hình mạch. Chức năng nhiệt độ và hai ngưỡng BLE giữ nguyên.

## Xác minh

Build và lint PASS. Android 35 giả lập: nearby và ble PASS. Kiểm tra hai nút, GIF, chọn hộp mới so với kết nối lại, quay lại danh sách giữ account, đóng scanner, danh sách trống không tự dò, quyền Bluetooth, RSSI, nhiều thiết bị, bỏ ghép nối, nhiệt độ và lưu ngưỡng. Đã chụp và kiểm tra bố cục trang danh sách ở 360dp. Dữ liệu và advertisement kiểm thử được giả lập; chưa thử app này trên điện thoại thật. APK đã tải lên VPS, tải lại HTTPS và so khớp SHA-256.
