# ThuầnPhongvlog 3.7.16

- Đổi tên ứng dụng thành **ThuầnPhongvlog**: tên dưới biểu tượng Android,
  tiêu đề trên các màn hình và mục giới thiệu.
- Thêm **Hotline: 0973210690** ngay sau tiêu đề **Tổng quan**, chữ nhỏ hơn
  tiêu đề để giữ bố cục gọn. Nhãn tab điều hướng vẫn là Tổng quan.
- Tên dự phòng khi thiết bị BLE không gửi tên dùng cùng tài nguyên tên app.

[Tải APK 3.7.16](https://103-9-78-139.sslip.io/downloads/ThuanPhongvlog-3.7.16.apk).

VersionCode 72, giữ applicationId `vn.duong.ms51connect` và chữ ký bản 3.7.15
để cài đè. Không đổi kết nối, dữ liệu ghép nối hoặc logic điều khiển.
Không cần cập nhật C5/MS51 cho thay đổi tên và hotline này.

## Kiểm chứng

- Build `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest` thành công;
  không có lint Error/Fatal.
- Kiểm tra nhãn trong APK là đúng ThuầnPhongvlog, version 3.7.16 và mã 72.
  Chứng chỉ APK mới trùng bản 3.7.15. SHA256 tệp ở `SHA256SUMS.txt`.
- Chạy fixture native `ui-preview` trên Android 35 ở 375 dp/font 1.0 và
  360 dp/font 1.3, cả hai PASS. Đã xem ảnh tiêu đề và hotline ở hai kích thước.
- Ảnh minh họa dùng dữ liệu giả lập, không gửi lệnh đến mạch thật.

[Tổng quan](overview-375dp.png) · [Đăng nhập](login-375dp.png)
