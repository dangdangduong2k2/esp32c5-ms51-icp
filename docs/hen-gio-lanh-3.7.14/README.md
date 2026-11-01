# Hẹn Giờ Lạnh 3.7.14

Android 3.7.14 (versionCode 70). Sau khi đã vào điều khiển qua BLE, app tự mở lại kết nối tới hộp đang dùng khi mất liên lạc. Bản cũ chỉ tiếp tục đọc qua đối tượng kết nối đã đóng nên không thể tự phục hồi.

- Tổng quan hiện “Đang kết nối lại…” khi đường BLE mất kết nối. Dữ liệu/điều khiển hết hạn không được thể hiện như dữ liệu đang sống.
- Thử lại với thời gian chờ 1, 2, 4, 8 rồi tối đa 15 giây giữa các lần thất bại; mỗi lần thiết lập lại GATT tối đa 15 giây.
- Chỉ dùng địa chỉ hộp đang chọn và xác minh lại mã thiết bị trước khi sử dụng kết nối mới.
- Giữ màn hình, vị trí cuộn và giá trị đang nhập. Các tab khác cũng phục hồi qua lần đọc nền tiếp theo.
- Không tự gửi lại hoặc xếp hàng lệnh chuyển chế độ/lưu cài đặt đã bấm khi mất kết nối. Lệnh chưa rõ kết quả cần đọc lại trạng thái rồi thao tác lại.
- Ngừng phục hồi khi app vào nền; tiếp tục khi trở lại. Thoát thiết bị hoặc bỏ ghép đóng phiên, không tự nối lại hộp cũ. Kết quả đến muộn sau khi hủy bị đóng.
- Thiết bị bị bỏ bond phải ghép lại; không tự tạo ghép nối mới trong quá trình phục hồi.

Chỉ cài đè APK, không cần nạp C5/MS51. Luồng ghép nối lần đầu vẫn riêng; thay đổi này xử lý mất kết nối sau khi đã vào màn điều khiển.

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.14.apk)

## Kiểm chứng

- Build APK và Android instrumentation, lint: thành công, không có Error/Fatal.
- `ble-recovery`: lỗi liên tiếp/backoff, chỉ một phiên khi đọc đồng thời, kiểm tra danh tính, không gửi lại lệnh mất ACK, từ chối lệnh trong lúc phục hồi, tạm dừng/tiếp tục/hủy và đóng kết nối đến muộn. Kiểm tra màn Tổng quan tự phục hồi mà không điều hướng ra trang dò.
- `ble`, `nearby`, `managed`, `scroll`: PASS.
- Kiểm tra bằng giả lập Android 35 và transport mô phỏng, không gửi lệnh tới hộp thật. Cần kiểm chứng radio bằng điện thoại sau khi cài APK.
- [Ảnh trạng thái tự nối lại](reconnecting.png) là giao diện native với dữ liệu thử.
