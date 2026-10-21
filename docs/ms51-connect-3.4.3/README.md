# MS51 Connect 3.4.3 · Giữ vị trí cuộn

Sửa lỗi màn hình Tổng quan tự cuộn xuống vùng nhập ngưỡng / nút **Lưu** khi dữ liệu cập nhật.

- Tiêu điểm tự động được giữ ở nền trang, thay vì tự chọn ô nhập hoặc nhật ký bên dưới.
- Khi người dùng đã cuộn khỏi ô đang giữ con trỏ/nhật ký, cập nhật nền bỏ tiêu điểm cũ trước khi thay nội dung, tránh Android kéo vùng đó trở lại màn hình.
- Không ghi lại nhật ký nếu nội dung không thay đổi. Ô nhập đang nhìn thấy vẫn giữ tiêu điểm, số đang nhập và vị trí con trỏ khi cập nhật định kỳ.

**Chỉ cập nhật APK. C5 và MS51 tiếp tục dùng bản 3.4.2; không cần nạp lại mạch.** Không thay đổi thuật toán nhiệt độ, ngưỡng an toàn, xả đá hay API.

Đã tái hiện trên APK 3.4.2 với màn hình thật gắn vào cửa sổ máy giả lập: phản hồi Lưu muộn làm vị trí cuộn nhảy từ 0 xuống 722 px. Bản 3.4.3 vượt qua kiểm tra cùng tình huống, cập nhật nhật ký, mất/kết nối lại, giữ vị trí đầu/giữa trang, nhập liệu đang hiển thị và bộ kiểm thử hồi quy giao diện. Build/lint không lỗi. Chưa kiểm tra trực tiếp trên điện thoại của người dùng.
