# Hẹn Giờ Lạnh 3.7.5 — Dò Bluetooth theo yêu cầu

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.5.apk)

Khi tài khoản đã có thiết bị ghép qua Wi-Fi/Internet, app không tự quét Bluetooth, yêu cầu bật Bluetooth hoặc hiện popup kết nối. Danh sách đã lưu vẫn áp dụng khi thiết bị offline hoặc máy chủ không truy cập được. Với tài khoản mới đăng nhập chưa có danh sách lưu, app đợi kết quả danh sách từ máy chủ trước khi tự dò; lỗi tải không được coi là danh sách trống.

Trang thiết bị có nút **Dò thiết bị**, thay cho Thêm thiết bị. Bấm nút bắt đầu quét, hiện **Đang dò… · Dừng dò**, chỉ mở popup GIF khi tìm thấy hộp phù hợp. Lần dò chủ động luôn cho chọn thiết bị, không tự chọn hộp đã nhớ. Dừng dò, đóng popup hoặc đưa app xuống nền sẽ kết thúc lần dò; quay lại trang có thiết bị Wi-Fi phải bấm dò lại. Yêu cầu cấp quyền/bật Bluetooth của hệ thống giữ ý định dò trong lúc chờ; hủy/từ chối thì dừng.

Tài khoản chưa ghép thiết bị Wi-Fi vẫn tự dò như trước. Trước đăng nhập, nếu điện thoại còn danh sách thiết bị Wi-Fi đã lưu thì app cũng không tự dò, và hiện nút dò tại trang đăng nhập. Luồng nhớ hộp BLE, chọn nhiều hộp, bỏ ghép nối và kết nối lại cho người dùng chỉ dùng BLE được giữ nguyên.

Chỉ cập nhật APK, versionCode 61, cùng chữ ký bản 3.7.4. Không sửa máy chủ, C5 hoặc MS51; không cần nạp lại bo.

## Kiểm tra

- Build Android, lint và test APK thành công, lint không có lỗi.
- Android 35 giả lập: `nearby` PASS, Bluetooth được bật để kiểm tra scanner/lifecycle. Kiểm tra tài khoản chưa có danh sách, danh sách rỗng, thiết bị đã ghép nhưng không có SSID, nút dò/dừng, popup chỉ hiện sau advertisement, hủy cấp quyền, xuống nền/quay lại, nhớ hộp và nhiều thiết bị.
- `ble` PASS: đóng gói thông điệp, UTF-8, giới hạn kích thước, định danh, cài đặt offline theo mạch và chỉ gửi tham số thay đổi.
- Advertisement trong test là giả lập; chưa thử lại bản app này trên điện thoại thật. Không gửi lệnh điều khiển tới bo trong lần kiểm tra này.
