# Gia hạn sử dụng từ web admin — MS51 3.7.5

Trong web admin, mở tài khoản → thiết bị → **Cấp quyền → Gia hạn sử dụng**.
Nhập thời gian 1–999 và chọn Giờ hoặc Ngày, rồi bấm Gia hạn. Mạch bắt đầu kỳ mới
khi xác nhận lưu; thời gian tính khi có nguồn theo cơ chế TRY. Khi hết, mạch lưu
shutdown và dừng chu trình như trước. Đây là cấp kỳ mới từ thời điểm gia hạn,
không phải cộng phần còn lại của kỳ cũ.

Mục này hiển thị trạng thái hết hạn/đang dùng/không giới hạn, thời gian còn lại
ước tính và kết quả lần gia hạn. Bộ đếm TRY hiện hữu lưu theo mốc 10 phút khi
dùng đơn vị giờ, 30 phút khi dùng đơn vị ngày; không phải hạn theo lịch Internet.
Thiết bị phải online để admin đọc và gia hạn. Cấp xong, mạch tự đếm khi offline.

## Triển khai

- Backend và giao diện đã được triển khai lên VPS hiện tại, có sao lưu SQLite
  và các file cũ trước khi thay. Admin API xác thực riêng và bảo vệ CSRF.
- TRY không còn là quyền chỉnh sửa dành cho app khách. Backend loại tryTime /
  tryUnit khỏi quyền hiển thị và từ chối ghi qua API cài đặt thông thường.
- C5 chuẩn R3/R4 và app 3.7.15 hiện có đã tương thích; không cần đổi giao thức
  C5 hoặc app để dùng gia hạn. Thiết bị MS51 cũ được báo cần cập nhật và không
  cho ghi bằng cách lưu đè toàn bộ cài đặt.
- File MS51 mới: [MS51-Usage-3.7.5.hex](https://103-9-78-139.sslip.io/downloads/MS51-Usage-3.7.5.hex).
  Source nằm tại `MS51_code/260502 add PIN`.
- Bo chuẩn DAT IO1, CLK IO0, RS IO3; NTC 10k IO5, 20k IO6, 50k IO4.
  Không thay đổi hoặc build các bản phần cứng test.

## Lưu và xác nhận

MS51 nhận một envelope SET riêng dài 217 byte, dấu `E3 TRY1`, gồm thời gian,
đơn vị và mã giao dịch 16 byte. Envelope không được chép lên cấu hình thông
thường. Firmware cũ từ chối dấu phiên bản này mà không thay đổi EEPROM.

Lệnh chỉ đổi try_time, đơn vị TRY, tried_time, shutdown và receipt riêng. Mật
khẩu, bộ đếm PIN, lịch sử, cấu hình relay, menu và chủ điều khiển không bị chép
đè. Đọc lại EEPROM trước khi ACK. Receipt được thêm cuối cấu trúc riêng, nằm
trong vùng EEPROM 256 byte; 217 byte giao thức cũ và các offset giữ nguyên.

Nếu mạch đang dừng vì hết TRY, nó khởi động lại sau ACK để thoát vòng dừng.
Nếu vẫn đang chạy thì giữ chu trình hiện tại. Gia hạn không mở các khóa PIN
riêng và không thay đổi bit khóa bảo vệ chương trình của chip.

Server lưu giao dịch trước khi gửi. Nếu mất ACK, đọc receipt mới từ telemetry
để đối chiếu hoặc thử lại cùng mã. MS51 không bắt đầu kỳ lần thứ hai khi đã lưu
mã đó. Server không gửi lại giao dịch đã xác nhận, kể cả đã có lần gia hạn mới
hơn; không cho tạo lần mới khi lần cũ còn chưa rõ kết quả.

Cơ chế này không thay thế phần cứng bảo vệ nguồn. EEPROM vẫn dùng thao tác
erase/write hiện có; mất điện hoặc hỏng flash khi ghi có thể cần sửa dữ liệu.
Không coi ACK hoặc receipt trong RAM là bằng chứng chống mọi lỗi vật lý.

## Kiểm chứng

- Host test chính hàm gia hạn: hết TRY, đang chạy, receipt sau reboot, không cấp
  lặp, giữ byte bảo vệ, lỗi đọc đối chiếu EEPROM, giới hạn và gói sai.
- Suite server gia hạn 21 tests, suite managed 45 tests: xác thực/CSRF, quyền sở
  hữu, app không tự đổi TRY, dữ liệu cũ/thiết bị chưa hỗ trợ, hết hạn, mất ACK,
  không nhận lệnh trước khi mất mạng và thử lại cùng mã.
- Hai suite trình duyệt: trạng thái, xác nhận lưu, offline, chưa hỗ trợ, retry
  giữ mã, cách ly dữ liệu giữa thiết bị và bố cục điện thoại. Preview riêng là
  fixture, không phải ảnh chứa dữ liệu tài khoản thực.
- Keil C51: 0 lỗi / 0 cảnh báo, code 30.041 byte, xdata 1.764; không có record
  HEX nào ghi vùng EEPROM từ 0x7F00.
- Bo thật `ms51-3844be1fe4e0`: đọc xác nhận chip không khóa; tải HEX mới, nạp có
  snapshot và đọc đối chiếu thành công trong khoảng 121 giây. CRC32 3404079354,
  recovery verified, không pending. Cả 217 byte cấu hình trước/sau giống nhau.
- API admin thực tế trả supported=true, trạng thái Không giới hạn và chưa có
  giao dịch gia hạn. Đã cập nhật bản ghi firmware đã cài theo đúng bằng chứng
  đối chiếu trên C5; phiên admin kiểm tra tạm thời đã được thu hồi.
- **Chưa cấp một kỳ thực cho khách**, vì người dùng chưa chọn số giờ/ngày.
  Trường hợp hết TRY rồi gia hạn lại được kiểm chứng trong test giả lập, chưa
  chủ động khóa/gia hạn mạch đang dùng để thử phá lỗi.

```powershell
python server/test_usage.py
python server/test_managed.py
node server/test_admin_usage_ui.cjs
node server/test_admin_access_ui.cjs
cc -std=c11 -Wall -Wextra -Werror tests/programmer/usage_renew_test.c -o D:/work/usage_renew_test.exe
D:/work/usage_renew_test.exe
```
