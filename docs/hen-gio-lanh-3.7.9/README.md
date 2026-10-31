# Hẹn Giờ Lạnh 3.7.9 — Chế độ offline riêng

[Tải app](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.9.apk)

Trang đăng nhập không tự quét, không xin quyền Bluetooth và không mở popup thiết bị. Bỏ các dòng nhắc đưa gần khỏi trang này. Nút nhỏ Chế độ offline bên dưới form mở màn riêng và tự bắt đầu dò, kèm GIF điện thoại tiến lại gần hộp. Hộp mới vẫn phải đạt điều kiện RSSI ổn định của 3.7.8 mới xuất hiện trong popup GIF xoay thiết bị; hộp đã ghép có thể tự kết nối lại sau khi người dùng vào offline.

Quay lại đăng nhập đóng scanner/popup. Khi ra nền, dừng dò; quay lại màn offline có thể bấm Dò thiết bị để tiếp tục. Yêu cầu quyền Bluetooth giữ đúng luồng đang chọn, hủy không tự dò tiếp. Trang danh sách tài khoản giữ cách dò của bản trước. Bỏ ghép nối khi chưa đăng nhập đưa về màn offline để chọn lại, không dò ngầm ở đăng nhập.

APK versionCode 65, chữ ký như bản trước, cài đè. Chỉ cập nhật app, không nạp C5/MS51 và không đổi cấu hình mạch. Giữ nhiệt độ và hai ngưỡng BLE 3.7.7.

## Xác minh

Build và lint đạt. Android 35 giả lập chạy nearby và ble PASS: trang đăng nhập không scanner/prompt, nút offline mở scanner và GIF, RSSI theo từng hộp, quay lại/ra nền/dừng dò, chọn nhiều hộp, bỏ ghép nối, kết nối lại, ba nhiệt độ và lưu hai ngưỡng. GIF hướng dẫn giải mã thành công trong Android; đã chụp và kiểm tra bố cục đăng nhập/offline ở 360dp. Advertisement trong kiểm thử được giả lập; chưa thử bản app này trên điện thoại thật.

GIF đóng gói sẵn, không tải mạng; tôn trọng cài đặt giảm chuyển động, giải phóng khi rời màn. Mã tạo GIF: tools/generate_offline_guide.py (Pillow). APK tải lên VPS, tải lại HTTPS và kiểm tra SHA-256 thành công.
