# MS51 Connect 3.5.0 — Mở khóa từ ứng dụng

Khi bộ điều khiển yêu cầu mật khẩu, ứng dụng đang mở màn hình thiết bị sẽ hiện hộp thoại **Mở khóa thiết bị**. Nhập mật khẩu thường hoặc mật khẩu dự phòng đúng với yêu cầu của mạch. Nút **Để sau** đóng hộp thoại; có thể mở lại từ nút **Mở khóa** trên Tổng quan.

- Giữ nguyên quy tắc kiểm tra mật khẩu, số lần nhập sai và khóa của MS51; nhập trực tiếp trên mạch vẫn dùng được.
- Không gửi lại lệnh tự động; vô hiệu hóa thao tác khi dữ liệu trạng thái cũ hoặc đang gửi lệnh.
- Cần cập nhật cả ứng dụng, C5 và MS51. Máy chủ đã hỗ trợ lệnh mới.
- Bo đang kết nối đã được nạp và xác minh cả hai chip; cấu hình và Wi-Fi được giữ nguyên, MQTT đã online.

Kiểm tra: build/lint Android, kiểm thử giao diện trên máy ảo, 36 kiểm thử máy chủ, kiểm thử logic PIN chính/dự phòng dùng hàm thực từ MS51, nạp/verify phần cứng. Mạch hiện chưa hỏi mật khẩu, nên chưa thử nhập đúng PIN trực tiếp trên mạch đang khóa.

## Giao thức

Telemetry `unlock_v=1`, `pin_state`: 0 không yêu cầu, 1 mật khẩu chính, 2 dự phòng, 3 khóa vĩnh viễn. App chỉ dùng trạng thái mới trong 3 giây.

`POST /api/unlock` nhận JSON `{"kind":1,"pin":123}`; kind là 1 hoặc 2, PIN nguyên 0–999. RPC máy chủ kiểm tra chủ thiết bị, giới hạn tốc độ và chặn khi đang cập nhật. UART dùng lệnh `0x57` với ba byte `[kind, pin_hi, pin_lo]`. Trả 200 khi thành công, 422 sai PIN, 409 khi trạng thái không phù hợp, 504 khi thiếu xác nhận. Không ghi PIN vào log.

C5 tự tính số route web từ bảng handler, tránh dừng máy chủ khi bổ sung endpoint.
