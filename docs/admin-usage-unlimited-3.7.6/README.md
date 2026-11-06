# Thời gian sử dụng 0 = không giới hạn — MS51 3.7.6

Trong **Tài khoản → thiết bị → Cấp quyền → Gia hạn sử dụng**:

- Nhập **0**, bấm **Gia hạn**: bỏ giới hạn TRY, kể cả mạch đã hết TRY.
  Mạch lưu `try_time=0`, xóa bộ đếm TRY cũ và cờ shutdown. Không tự khóa lại
  vì TRY, kể cả sau tắt/bật nguồn. Đơn vị giờ/ngày không có tác dụng với số 0.
- Nhập **1–999 giờ/ngày**: bắt đầu kỳ mới khi mạch xác nhận lưu, đếm khi có
  nguồn; hết thời gian sẽ khóa TRY lại. Có thể đổi từ không giới hạn sang kỳ
  hữu hạn bằng một lệnh gia hạn mới.
- Chỉ admin được thay đổi thời hạn. Khóa PIN, mật khẩu, bộ đếm bảo vệ PIN,
  relay và các cài đặt khác được giữ nguyên. Đây là mở TRY, không xóa khóa
  bảo vệ chương trình chip hoặc thay thế cơ chế PIN.
- **Đọc lại từ mạch** điền lại thời gian TRY và đơn vị đang lưu trên MS51,
  kể cả đã cài từ trước khi dùng web gia hạn. Hiển thị riêng thời gian còn
  lại ước tính; không cấp kỳ mới, không xóa bộ đếm. Nếu đang có lệnh chưa rõ
  kết quả, các ô giữ đúng giá trị lệnh chờ để thử lại an toàn. Mạch lưu kỳ
  hiện tại; không phải danh sách toàn bộ các kỳ cũ đã bị gia hạn thay thế.

## Tương thích và lưu xuống mạch

- [MS51-Usage-3.7.6.hex](https://103-9-78-139.sslip.io/downloads/MS51-Usage-3.7.6.hex).
- Source: `MS51_code/260502 add PIN`. Phần cứng chuẩn DAT IO1, CLK IO0, RS IO3;
  NTC 10k IO5, 20k IO6, 50k IO4. Không sửa/build bản phần cứng test.
- C5 chuẩn hiện tại và app không cần cập nhật cho chức năng admin này.
- Telemetry `usage_v=2` xác nhận MS51 nhận được thời gian 0. Web và server vẫn
  cho phép cấp kỳ hữu hạn với `usage_v=1` (MS51 3.7.5), nhưng từ chối gửi 0
  và báo cập nhật MS51. Firmware chưa có receipt bị chặn toàn bộ lệnh gia hạn.
- Giữ nguyên envelope 217 byte `E3 TRY1` và receipt EEPROM 16 byte. Không đổi
  các offset cấu hình cũ. Đọc đối chiếu EEPROM trước ACK. Nếu mất phản hồi,
  đọc receipt hoặc thử lại cùng mã; không cấp lại kỳ đang chạy.
- TRY đã hết: khởi động lại sau ACK để thoát vòng dừng. Mạch đang chạy: giữ
  chu trình, không khởi động lại chỉ vì đổi thời hạn.
- Quy trình EEPROM vẫn có giới hạn vật lý khi mất điện/hỏng flash trong lúc
  ghi, như bản 3.7.5; không tuyên bố chống được mọi lỗi nguồn.

## Kiểm chứng

- Host test chạy chính hàm C: số 0 khi đang chạy/đã khóa TRY, đọc lại sau
  reboot, chống cấp lặp, đổi lại kỳ hữu hạn, giữ byte bảo vệ và lỗi EEPROM.
- Server: 24 tests đạt, gồm mở 0, mất ACK, thử lại, xác thực/CSRF, chặn app
  đổi TRY và từ chối gửi 0 tới MS51 cũ. Managed regression: 45 tests đạt.
- Hai suite trình duyệt đạt: hữu hạn/không giới hạn, nhận biết MS51 cũ,
  trạng thái lỗi, retry cùng mã, quyền và bố cục điện thoại.
- Keil C51: 0 lỗi, 0 cảnh báo; code 30.038 byte, xdata 1.764. HEX không có
  record ghi vùng EEPROM từ 0x7F00. CRC32 ảnh chương trình: 2608586391.
- VPS đã triển khai và kiểm tra health, đối chiếu đủ 5 file với source; có
  sao lưu SQLite và file cũ. Cache phiên bản giao diện được đổi để tải mã mới.
- Bo thật `ms51-3844be1fe4e0`: cập nhật bảo vệ và đọc đối chiếu thành công
  trong 117 giây; recovery sequence 3, verified, không pending. Cả 217 byte
  cấu hình trước/sau giống nhau. Route truyền tạm thời đã được gỡ bỏ.
- API admin thật xác nhận `unlimited_supported=true`. Kỳ 4 giờ do admin đã
  cấp được giữ nguyên; tác vụ này không tự cấp lại kỳ hoặc gửi lệnh mở 0
  tới mạch thật. Nhánh mở 0 đã được kiểm chứng trong host/API/UI tests.

Kết quả triển khai và cập nhật phần cứng được lưu trong thư mục kết quả tác vụ
`D:/Codex/Documents/2026-11-06/admin-usage-unlimited/outputs`. Ảnh preview ở đó
là dữ liệu giả lập, không chứa thông tin tài khoản thật.

SHA256 tệp tải về nằm trong `SHA256SUMS.txt` cạnh tài liệu này. Bản 3.7.5 và
tài liệu triển khai ban đầu vẫn được giữ để đối chiếu lịch sử.
