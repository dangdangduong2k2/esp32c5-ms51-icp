# Hẹn Giờ Lạnh 3.6.0

Bản Android đổi giao diện MS51 Connect sang Hẹn Giờ Lạnh: xanh lạnh, nền trắng và thanh tiêu đề xanh đậm. Giữ định danh ứng dụng và chữ ký của bản 3.5.0 để cài đè.

- Logo đồng hồ/bông tuyết và tiêu đề nổi bật; không dùng hình xe hoặc slogan.
- Xả đá dùng biểu tượng viên đá đang tan. Nhiệt độ giám sát chỉ hiển thị 50k; nút đổi 10k/20k nằm ở nhiệt độ động cơ.
- Khởi động nằm trong Cài đặt nâng cao, mặc định thu gọn. Đóng/mở nhóm giữ giá trị chưa lưu. Các nhóm nâng cao khác cũng có thể thu gọn.
- Mũi tên tăng/giảm cho các ô số và giới hạn, giữ lâu tăng tốc; giao diện chuyển thành một cột khi tăng cỡ chữ.
- Giữ thao tác Lưu, quyền quản trị, ghép nối, cập nhật và mở khóa từ xa hiện có.
- Giữ vị trí cuộn khi nhận dữ liệu hoặc phản hồi lưu, kể cả khi ô đang nhập chỉ còn hiện một phần.

## Cài đặt

Tải `Hen-Gio-Lanh-3.6.0.apk` và cài đè app cũ. Không gỡ bản cũ trước khi cập nhật. Android 7.0 trở lên. App sẽ mang tên **Hẹn Giờ Lạnh** sau khi cài.

Đây là bản cập nhật giao diện Android; không cần nạp lại C5/MS51 đang dùng bản 3.5.0 cho thay đổi này. APK ký bằng cùng khóa debug của các bản thử trước; chưa phải bản phát hành Google Play.

## Kiểm tra

Build thành công; Android lint 0 lỗi, 54 cảnh báo. Các kiểm tra offline đã qua: quyền hiển thị, nhóm cài đặt, hai ngưỡng, chế độ, đồng hồ đếm, cập nhật, nhật ký, menu chọn, nút giữ tăng tốc, vị trí cuộn, mở khóa. Kiểm tra ảnh ở 360dp/411dp và cỡ chữ 130%. Không gửi lệnh tới bo thật trong lần kiểm thử này.

Ảnh chụp là giao diện Android thật với dữ liệu minh họa. Xem `test-results.json` để biết phạm vi và mã SHA-256.
