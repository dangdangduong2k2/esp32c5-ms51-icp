# MS51 3.4.1 · SIG vật lý

MS51 trở lại đọc **chân SIG vật lý P0.1, active-low**, bất kể cảm biến 10k/20k/50k, ngưỡng đã lưu, lệnh từ app hay trạng thái liên lạc với C5.

Giữ nguyên APK 3.4.0 và bản C5 hiện tại. Ô Ngưỡng ngắt vẫn hiển thị/lưu trên app nhưng không còn thay SIG hoặc tác động logic ngắt của MS51. Thông báo tính theo nhiệt độ trong app cũ không xác nhận trạng thái SIG vật lý.

Phần ngưỡng xả đá 50k điều khiển riêng relay 3 và cơ chế trở về mạch khi lỗi/mất dữ liệu giữ nguyên. Không đổi bố cục cấu hình hoặc chân đấu dây.

Chỉ nạp **MS51-Physical-SIG-3.4.1.hex vào MS51** qua quy trình nạp của C5 hoặc chức năng cấp file cập nhật của admin. Không nạp file này vào ESP32-C5 bằng esptool-js. Lần này chỉ xuất file theo yêu cầu, **chưa nạp lên bo**.

Đã build Keil: 0 lỗi, 0 cảnh báo; kiểm thử trực tiếp logic C với SIG cao/thấp, mọi nguồn đã lưu, lệnh cũ/mới, hết hạn liên lạc và khởi động lại; kiểm thử hồi quy relay 3. Chưa kiểm tra điện trên bo thật cho bản này.
