# Hẹn Giờ Lạnh 3.7.8 — Đưa gần để ghép hộp mới

[Tải app](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.8.apk)

Thiết bị chưa ghép trong app chỉ xuất hiện trong popup khi tín hiệu Bluetooth đủ mạnh và ổn định. Áp dụng cho cả tự dò ở trang đăng nhập và bấm Dò thiết bị. Mỗi hộp được xét riêng, không cộng tín hiệu của nhiều hộp. Hộp đã xác thực ghép trong app và vẫn còn bond Android bỏ qua điều kiện gần; hành vi chọn lại/tự nối thiết bị gần nhất giữ nguyên. Bỏ ghép nối hoặc mất bond sẽ yêu cầu đưa gần khi ghép lại.

Điều kiện ban đầu: RSSI từ -45 dBm, ít nhất 3 mẫu hợp lệ trải dài tối thiểu 600 ms trong cửa sổ 2 giây. Mẫu yếu làm bắt đầu lại; mẫu cũ, trùng, tương lai và RSSI không hợp lệ không được dùng. Sau khi hộp đủ điều kiện trong phiên dò hiện tại, popup không chớp/tắt theo dao động tín hiệu. Ngưỡng này là gợi ý khoảng cách, không phải đo chính xác cm hay cơ chế xác thực; cần tinh chỉnh theo điện thoại/vỏ hộp thực tế nếu cần.

Có hướng dẫn đưa điện thoại sát hộp mới tại trang đăng nhập và khi dò thủ công. Chỉ cập nhật app (versionCode 64), không nạp C5/MS51, không đổi cấu hình mạch hay máy chủ. Cài đè bản trước; chữ ký khớp bản 3.7.7.

## Kiểm tra

Build APK/test APK và lint đạt. Android 35 giả lập chạy nearby và ble PASS: ngưỡng tín hiệu, nhiều mẫu, riêng từng thiết bị, tín hiệu yếu, mẫu cũ/trùng/lỗi, quay lại gần, bỏ ghép nối, kết nối lại hộp đã ghép với RSSI -90, tự dò trang đăng nhập, dò thủ công trang thiết bị, popup GIF, nhiều hộp, vòng đời và hủy quyền. Các chức năng nhiệt độ/ngưỡng BLE 3.7.7 tiếp tục PASS.

Advertisement và RSSI được giả lập trong kiểm thử; chưa đo khoảng cách thực với điện thoại người dùng. APK đã tải lên VPS và tải lại HTTPS đối chiếu SHA-256.
