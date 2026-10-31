# Hẹn Giờ Lạnh 3.7.3 — Bỏ ghép nối và chọn hộp

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.3.apk)

Trong tab **Thiết bị**, nút **Bỏ ghép nối** cho phép quên hộp đang dùng trong app và mở lại danh sách tìm hộp. Nút có xác nhận và chỉ xóa liên kết của hộp đó trên điện thoại; không xóa tài khoản, liên kết trên máy chủ, Wi-Fi hoặc cài đặt mạch. **Ngắt kết nối** vẫn giữ hộp đã nhớ để lần sau tự nối lại.

Khi quét thấy nhiều hộp, popup chuyển sang **Chọn thiết bị**. Mỗi hộp có nút riêng với tên và phần cuối địa chỉ Bluetooth; không dùng hình minh họa để tự chọn hộp đầu tiên. Quảng bá lặp không tạo nút trùng.

Sau khi bỏ ghép hoặc bấm Thêm thiết bị, người dùng chọn hộp kể cả khi một hộp khác từng được nhớ. Chỉ sau khi kết nối và xác minh đúng định danh, app mới nhớ hộp được chọn làm đích tự kết nối. Từ lần mở sau: popup hình xoay → tự kết nối → Tổng quan, như 3.7.2. Bỏ ghép rồi đóng/mở lại app vẫn yêu cầu chọn lại, không tự chuyển sang một hộp cũ khác.

## Phạm vi bỏ ghép

Đây là bỏ liên kết trong **app**, không xóa khóa ghép Bluetooth của Android hoặc phía C5. Muốn xóa cả ghép Bluetooth hệ thống, dùng Cài đặt Bluetooth trên điện thoại. Luồng hiện tại dùng `BluetoothDevice.createBond`, chưa tạo association qua CompanionDeviceManager; không gọi API ẩn `removeBond` bằng reflection. API công khai `CompanionDeviceManager.removeBond` cần association và Android API 36: [tài liệu Android](https://developer.android.com/reference/android/companion/CompanionDeviceManager#removeBond(int)).

## Kiểm thử

- Build APK 3.7.3/code 59, lint không có lỗi, cùng chữ ký với bản trước.
- Android 35 giả lập: quét tự động, GIF, hai hộp có hai lựa chọn, chạm hình không chọn nhầm khi có nhiều hộp, trùng quảng bá, chọn đúng hộp thứ hai.
- Bỏ ghép xóa đúng hộp, không tự kết nối hộp đã quên hoặc tự chọn hộp cũ khác; quét lại hiện đủ lựa chọn; kết nối thành công sau đó trở thành hộp tự kết nối.
- Giữ kiểm tra BLE/offline và hồi quy cấu hình/quyền bằng các chế độ `ble`, `managed`.

Chỉ cài đè app. Không cần nạp lại C5/MS51; chưa kiểm tra tình huống hai bo thực qua radio ở bản này.
