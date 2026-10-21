# MS51 3.4.4 · Báo đếm HCF OP

Sửa tín hiệu báo đếm để ô **Thời gian hoạt động** trên app nhấp nháy suốt lúc thời gian **HCF OP / CF** đang chạy, kể cả khi số trên mạch giữ ở **0** hoặc chưa thay đổi.

Bỏ điều kiện cũ trả về NONE riêng cho HCF OP. Khi bộ đếm thực sự đang chạy và màn hình xanh là số, MS51 gửi `countdown_active=GREEN`. Vẫn dừng nhấp nháy khi bộ đếm bị giữ, tắt máy, cài đặt, xem lịch sử hoặc popup. CL tiếp tục báo ô Thời gian chờ. Không thay số hiển thị hoặc cách đếm trên mạch, không sửa thuật toán ngưỡng an toàn/relay.

**Chỉ cập nhật MS51. Giữ APK 3.4.3 và C5 3.4.2.** File HEX nạp vào MS51 qua C5/admin, không nạp vào ESP32 bằng esptool-js.

Đã nạp và xác minh trên bo đang kết nối, cấu hình MS51 trước/sau giống nhau. Sau nạp bo đang ở CL, phản hồi RED đúng pha. CF OP với số 0 và các trường hợp dừng được kiểm thử bằng logic C thật và kiểm thử app trên máy giả lập; chưa quan sát trực tiếp ô nhấp nháy trên điện thoại người dùng ở pha CF OP.

Keil: 27.992 byte mã, 0 lỗi, 0 cảnh báo. Kiểm thử hồi quy ngưỡng an toàn OP1, SIG vật lý, relay 3 và cấu hình C5 đều đạt.
