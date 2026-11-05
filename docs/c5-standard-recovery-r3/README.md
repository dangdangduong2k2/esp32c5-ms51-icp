# C5 Standard Recovery R3

Bản chuẩn duy nhất được phát hành trong thay đổi này. Không chỉnh sửa hoặc build
bản phần cứng test IO1/N4.

## Chân chuẩn và dung lượng

| Chức năng | GPIO |
|---|---|
| DAT | 1 |
| CLK | 0 |
| RS | 3 |
| NTC 10k | 5 |
| NTC 20k | 6 |
| NTC 50k | 4 |

Gói `ESP32-C5-STANDARD-RECOVERY-R3.zip` cấu hình flash **4 MB, DIO, 80 MHz**;
có thể dùng với C5 có flash 4 MB hoặc lớn hơn. Tất cả các phân vùng hiện có kết
thúc ở 0x235000, nằm trong 4 MB. Không cần thay đổi chân mạch để dùng gói này.
Bảng phân vùng và vị trí NVS giữ nguyên so với Recovery R1/R2.

## Nội dung

- Tích hợp bộ nhận HEX ít RAM từ OTA RAM R1: bản đồ coverage 4 KiB và ghi vào
  slot flash dự phòng, không giữ toàn bộ HEX/canonical image 36 KiB trong RAM.
- Giữ bản cũ/bản mới và nhật ký phục hồi từ Recovery R1.
- Giữ kiểm tra khóa qua hai lần đọc CONFIG từ Recovery R2. Không tự xóa MS51.
- Thêm `firmware` vào `/api/status`, gồm phiên bản, mã build ELF, kích thước flash
  vật lý, flash cấu hình, chân DAT/CLK/RS, RAM trống và khối trống lớn nhất.
  Máy chủ nhận các trường này qua phản hồi trực tiếp và heartbeat để xác nhận
  chính xác bản đang chạy. Thông tin này không được chèn vào giao diện người dùng.
- App Android 3.7.15 vẫn dùng được. Giữ Wi-Fi, BLE và tài khoản hiện có.

Máy chủ cũng phân biệt lỗi trước/sau lệnh nạp: tải file thất bại hoặc sai CRC
trước khi gửi `/api/program` được đánh dấu thất bại, cho phép bắt đầu lượt mới.
Nếu đã gửi lệnh nạp và mất phản hồi thì vẫn giữ chưa xác định, cần đối chiếu
trạng thái phục hồi; không tự gửi lặp lệnh nạp.

## Nạp C5 qua USB

Dùng ba file từ cùng gói, không chọn Erase All:

| Địa chỉ | File |
|---|---|
| 0x2000 | bootloader.bin |
| 0x8000 | partition-table.bin |
| 0x10000 | ESP32-C5-STANDARD-RECOVERY-R3-app.bin |

Ngắt kết nối nạp sau khi hoàn thành, thả BOOT và khởi động lại bình thường.
Không cập nhật C5 khi MS51 đang có tác vụ phục hồi pending.

Sau khi nạp cần nhận phản hồi **mới** từ đúng mã thiết bị:

- `firmware.version = C5-STD-RECOVERY-R3`.
- `firmware.pins = {dat:1, clk:0, reset:3}`.
- NTC 10k/20k/50k ở GPIO 5/6/4.
- `recovery.available = true`, `recovery.pending = false` trước khi nạp MS51.

Nạp C5 không đồng nghĩa đã mở khóa MS51. Nếu MS51 khóa bảo vệ, cần xác nhận bit
khóa, sao lưu cài đặt đọc được và thực hiện quy trình xóa cứu hộ riêng. Xóa toàn
bộ MS51 sẽ mất chương trình, dữ liệu flash và CONFIG. Xem tài liệu Recovery R2.

## Kiểm chứng và giới hạn

Gói được build từ source chuẩn bằng ESP-IDF 6.0, version `C5-STD-RECOVERY-R3`.
Các kiểm tra đóng gói đối chiếu version, header flash 4 MB, cấu hình chân,
bảng phân vùng và SHA256. Bộ kiểm thử server bao gồm lỗi thiếu RAM khi tải,
lượt thử mới sau lỗi trước khi nạp, mất ACK sau khi nạp và các trường hợp phục hồi.

Bo ECB490 ở xa. Phản hồi trực tiếp trước khi nạp R3 còn báo IO1, chưa có trường
Recovery, không khớp gói chuẩn R2 đã phát hành. Đây là quan sát khi chẩn đoán,
không phải kết luận về thao tác nạp của người dùng. R3 bổ sung version/build để
xác minh trạng thái sau nạp. Chưa xác minh R3 hoặc tải HEX qua Internet trên bo
này; cần hoàn tất kiểm tra sau khi người tại chỗ nạp qua USB.

Các giới hạn về mất nguồn riêng của C5, đứt RS/GND, phần cứng khóa tải và dữ liệu
không nằm trong APROM vẫn áp dụng; xem `docs/c5-recovery-r1/README.md`.
