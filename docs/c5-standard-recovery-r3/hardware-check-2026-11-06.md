# Kiểm tra R3 trên bo đang bật, 06/11/2026

Thiết bị: `ms51-3844be1fe4e0`. Người dùng xác nhận chỉ mạch mới nhất đang bật.
Registry lúc kiểm tra chỉ còn thiết bị này; không dùng tên New/Old làm bằng chứng
nhận dạng phần cứng.

## Trạng thái

- Heartbeat báo `C5-STD-RECOVERY-R3`, chân DAT 1, CLK 0, reset 3.
- NTC 10k/20k/50k ở GPIO 5/6/4.
- Flash vật lý 16 MB, cấu hình dùng 4 MB.
- Vùng phục hồi available, không pending, sequence 0.
- RAM trống khoảng 45 KB, khối lớn nhất khoảng 21 KB trước thử tải.

## Nhận HEX qua HTTPS

- File `MS51-Bluetooth-3.7.4.hex`, 84.552 byte, SHA256
  `e33b07e273a48d827f9cfca6eb62c5550f226d7889610752264cfbe38ef4f962`.
- Gọi riêng `/internal/upload`, không gọi `/api/program`, `/api/verify` hoặc xóa
  chip. Đường đọc HTTPS chính là đường tải cloud của firmware R3.
- Kết quả thành công sau 5,41 giây; coverage 29.159 byte, canonical size 29.160,
  CRC32 820705851; generation tăng từ 12 lên 13.
- Đọc cấu hình MS51 qua UART trước/sau giống nhau. Recovery vẫn idle, sequence 0,
  không pending. Thử nghiệm này chỉ ghi slot upload trên C5, chưa nạp MS51.
- Dùng URL transfer chẩn đoán có token riêng, phục vụ cùng file HEX đã kiểm hash.
  Cấu hình Nginx được sao lưu, kiểm tra và khôi phục nguyên văn sau khi thử;
  route chẩn đoán đã gỡ. Không lưu token hoặc cấu hình người dùng vào Git.

## Lỗi thời gian còn cần xác minh

GET mới với timestamp máy chủ bị từ chối là lệnh không hợp lệ/hết hạn. GET cùng
cấu trúc với timestamp lùi 30 giây được nhận. Đồng hồ máy chủ và HTTPS Date bên
ngoài khớp nhau trong vài giây. Đây là bằng chứng phù hợp với đồng hồ C5 chậm
khoảng 5–35 giây, chưa phải số đo thời gian chính xác trên C5.

Lượt thử tải riêng dùng cùng cửa sổ sống 45 giây và timestamp theo khoảng được
C5 chấp nhận. Không tắt kiểm tra thời hạn và không thay đổi timestamp của API
sản xuất. Kết quả nhận HEX không chứng minh luồng app cập nhật MS51 đã hoàn tất.

Người dùng đã RESET/EN C5. Ban đầu máy chủ ghi nhận offline trong khi BLE vẫn
được điện thoại dò thấy. Sau đó bo trở lại cloud và nhận GET mới với timestamp
bình thường của máy chủ, không cần điều chỉnh giờ lệnh.

Chạy lại toàn bộ thử nhận HEX bằng timestamp bình thường: thành công sau 5,55
giây, CRC32 820705851, generation tăng từ 13 lên 14. Cấu hình MS51 trước/sau
giống nhau, recovery vẫn idle/sequence 0, không gọi lệnh nạp hoặc xóa MS51.
Route transfer chẩn đoán tiếp tục được gỡ và Nginx được khôi phục sau thử nghiệm.
Đây là lỗi kết nối/đồng bộ giờ riêng, không phải lỗi thiếu RAM ở bước nhận HEX
trên bản R3 chuẩn đã thử.

Kiểm tra source cho thấy SNTP chỉ được bắt đầu khi RTC chưa có năm hợp lệ.
RTC có giá trị nhưng lệch sẽ bỏ qua bước bắt đầu SNTP trong lần boot đó.
Bản R4 sửa điều kiện này, thêm đồng bộ định kỳ, yêu cầu đồng bộ lại khi thời
hạn lệnh không khớp và thông tin giờ trong trạng thái. Chưa nạp R4 trên bo này.
