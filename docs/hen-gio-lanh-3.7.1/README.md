# Hẹn Giờ Lạnh 3.7.1 — Tìm thiết bị và kết nối bằng popup

[Tải APK](https://103-9-78-139.sslip.io/downloads/Hen-Gio-Lanh-3.7.1.apk)

- Bỏ nút “Dùng Bluetooth · Không cần Internet” trên màn hình đăng nhập.
- Khi mở app ở màn hình đăng nhập/danh sách thiết bị, tự quét Bluetooth. Nếu thiếu quyền hoặc Bluetooth tắt, Android hỏi xác nhận; sau khi đồng ý app tự tiếp tục, không cần bấm tìm lại.
- Chỉ hiện popup khi tìm thấy hộp có dịch vụ BLE tương thích. Popup dùng đúng GIF v5 đã duyệt: hộp nhựa, hai nút xoay cùng hộp, LED xanh lá. GIF được đóng gói trong APK, không cần mạng.
- Chạm hình hộp hoặc “Kết nối” một lần để ghép và mở thẳng Tổng quan. Bỏ trang trung gian “Dùng không Internet”. Yêu cầu ghép đôi của Android, nếu có, vẫn do hệ điều hành xử lý.
- Nhiều thiết bị được phân biệt bằng tên và phần cuối địa chỉ Bluetooth. Không tự chọn hoặc tự gửi lệnh điều khiển khi mới quét thấy.
- Thêm Wi-Fi/tài khoản vẫn ở mục Thiết bị. Nút “Thêm thiết bị” trong danh sách cũng tìm qua BLE.
- Đóng popup thì không mở lại trong cùng lần ở foreground; ra nền dừng quét và bỏ popup. Không chen popup vào màn hình đang điều khiển hoặc đang lưu/kết nối. Quét 15 giây, nghỉ 10 giây khi chờ ở màn hình đầu.
- Ảnh xoay theo nhịp vẽ màn hình; khi người dùng tắt chuyển động của Android thì giữ ảnh tĩnh.

## Cài đặt và giới hạn kiểm thử

Cài đè APK 3.7.1, cùng chữ ký với 3.7.0. Lần sửa giao diện này chỉ đổi app, không đổi server/C5/MS51. Bo cần firmware hỗ trợ BLE. **Bo thử đã nạp C5 r2 ngày 31/10/2026 sau báo cáo tự ngắt, người dùng kết nối lại và báo hiện ổn**; xem [kiểm tra trên bo](../hen-gio-lanh-3.7.0/hardware-check-2026-10-31.md).

Đã chạy build, lint (không có lỗi) và instrumentation Android 35: tự khởi động quét, dừng khi ra nền, popup chỉ hiện sau sự kiện tìm thấy, GIF thực sự đổi khung hình, chống quảng bá trùng/chạm đúp, chọn đúng thiết bị, đóng popup, mở thẳng điều khiển BLE, cài đặt offline và hồi quy các mục online. Sự kiện tìm thấy hộp trong kiểm thử được mô phỏng; chưa xác nhận kết nối vô tuyến với C5 thật ở lần sửa này.

Android không cho app thông thường tự bật Bluetooth một cách im lặng; dùng hộp thoại hệ thống [ACTION_REQUEST_ENABLE](https://developer.android.com/develop/connectivity/bluetooth/setup).
