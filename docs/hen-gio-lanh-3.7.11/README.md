# Hẹn Giờ Lạnh 3.7.11 — Nới khoảng cách tìm hộp mới

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.11.apk)

Nới điều kiện RSSI cho hộp chưa ghép từ -45 dBm sang -50 dBm để dễ phát hiện ở xa hơn một chút. Giữ ít nhất ba mẫu ổn định trong hai giây, trải dài ít nhất 600 ms; mẫu yếu vẫn xóa chuỗi, mẫu trùng/cũ/lỗi không được tính. Thiết bị đã ghép vẫn kết nối lại theo luồng cũ. RSSI không tương đương một khoảng cách cm cố định; cần thử với điện thoại/vỏ hộp thực tế.

Bao gồm thay đổi 3.7.10: trang danh sách có nút Dò thiết bị mới và ô Chế độ offline, cả hai có GIF hướng dẫn; quay lại giữ tài khoản. Trang đăng nhập và danh sách không tự popup. Chỉ cập nhật app versionCode 67, không nạp C5/MS51.

## Kiểm tra

Build/lint và Android 35 giả lập nearby/ble PASS. Bổ sung kiểm thử -50 dBm ổn định đủ điều kiện; -51 dBm không đủ; một hoặc hai mẫu vẫn phải chờ. Tiếp tục kiểm tra hai lối vào, GIF, quay lại giữ tài khoản, RSSI từng hộp, bỏ ghép nối, ba cảm biến và hai ngưỡng qua BLE. Chữ ký như bản trước. APK tải lên VPS và tải lại HTTPS đối chiếu SHA-256. Chưa đo lại khoảng cách thực trên điện thoại người dùng.
