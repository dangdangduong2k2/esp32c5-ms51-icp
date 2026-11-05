# C5 Recovery R2 — chẩn đoán và cứu hộ MS51 bị khóa

## Sửa lỗi

R1 cho phép đọc CONFIG của chip có đúng PDID nhưng CID bị che, sau đó lại yêu cầu
CID bình thường ở lần vào ICP tiếp theo. Vì vậy `info` và cả lệnh xóa cứu hộ có thể
bị chặn với `ESP_ERR_NOT_FOUND` dù đã đọc được mã MS51FC0AE.

R2 đọc CONFIG ở hai lần vào ICP, yêu cầu chúng khớp nhau. Bit CONFIG0.1 xác định
trạng thái khóa, thay vì chỉ suy đoán từ CID. CID 0xFF chỉ được chấp nhận khi đúng
PDID và CONFIG ổn định xác nhận khóa. CID khác 0xDA/0xFF, CID 0xFF với CONFIG không
khóa, hoặc CONFIG không ổn định đều bị từ chối. Log in năm byte CONFIG và trạng
thái khóa để chẩn đoán.

Chip khóa có thể được đọc thông tin và xử lý bằng lệnh whole-chip erase riêng,
có xác nhận. Nạp thường, đọc APROM và verify vẫn bị chặn. R2 không tự xóa chip khi
OTA thất bại, không bỏ qua kiểm tra khóa và không khôi phục chương trình được
bảo vệ bằng cách đọc APROM qua ICP.

## Gói nạp

`ESP32-C5-STANDARD-RECOVERY-R2.zip` dành cho **C5 chuẩn 16 MB**:

- DAT IO1, CLK IO0, RS IO3.
- NTC 10k IO5, 20k IO6, 50k IO4.
- Giữ Bluetooth, Internet và vùng phục hồi R1.
- Không tự nạp hoặc xóa MS51 khi C5 khởi động.
- App Android 3.7.15 vẫn sử dụng được, không cần APK mới cho bản sửa này.
- Không dùng gói này cho bản test IO1/N4 4 MB.

| Địa chỉ | File |
|---|---|
| 0x2000 | bootloader.bin |
| 0x8000 | partition-table.bin |
| 0x10000 | ESP32-C5-STANDARD-RECOVERY-R2-app.bin |

Không chọn Erase All. Nếu bo đã dùng Recovery R1 đúng bảng phân vùng, chỉ cần nạp
app ở 0x10000. Bo chưa có vùng `ms51safe` cần cả bảng phân vùng đi kèm. Không thay
firmware C5 khi tác vụ phục hồi MS51 còn pending. Gói chỉ cập nhật C5; nạp xong C5
chưa có nghĩa MS51 đã được mở khóa.

## Quy trình cứu hộ cần người vận hành

1. Sao lưu mới các cài đặt MS51 qua UART/Internet khi chương trình cũ còn chạy.
   Bản sao giao thức cấu hình không bảo đảm chứa toàn bộ bộ đếm, lịch sử hoặc
   vùng riêng của bootloader. Giữ file HEX, thông tin CONFIG và bản sao ở nơi khác.
2. Đọc thông tin chip bằng `info` hoặc POST `/api/info`. Thao tác vào ICP sẽ reset
   MS51. Chỉ kết luận khóa sau khi nhận đúng chip, CONFIG ổn định và `locked=true`.
3. Whole-chip erase là lệnh riêng, chỉ thực hiện khi đã chấp nhận mất chương trình,
   dữ liệu flash và CONFIG. Không thực hiện nếu thiếu file nạp/cấu hình cần thiết
   hoặc không thể chấp nhận dừng bộ điều khiển.
4. Nạp HEX chuẩn, đối chiếu thành công, kiểm tra chip không khóa và phục hồi cài đặt.
   Mạch chỉ được đưa vào vận hành sau khi kiểm tra lại các đầu ra và dữ liệu.

Theo tài liệu Nuvoton MS51FC0AE, CONFIG vẫn đọc được khi chip khóa; mở khóa bằng
whole-chip erase sẽ xóa flash và CONFIG. IAP không bị ảnh hưởng bởi khóa, nhưng
cơ chế cập nhật hiện tại của dự án dùng ICP, chưa có đường cập nhật MS51 qua IAP.

Nguồn: https://www.nuvoton.com.cn/export/resource-files/TRM_MS51EB0AE_MS51FC0AE_MS51XC0BE_MS51EC0AE_MS51TC0AE_MS51PC0AE_EN_Rev1.02.pdf
(trang 28, CONFIG0.1).

## Kiểm chứng

Host test `tests/programmer/lock_test.c` chạy chính `ms51_programmer.c` với target
ICP giả lập: chip khóa/CID che, CID sai, chip khác, CONFIG thay đổi, chặn đọc/nạp/
verify vào chip khóa, không tự xóa, xóa cứu hộ tường minh và kiểm chứng, lỗi xóa,
chặn khi đang phục hồi, và trả quyền UART sau lỗi.

```powershell
cc -std=c11 -Wall -Wextra -Werror -I tests/programmer -I tests/image -I main tests/programmer/lock_test.c main/ms51_image.c -o D:/work/lock_test.exe
D:/work/lock_test.exe
```

Gói được build bằng ESP-IDF 6.0, phiên bản `C5-RECOVERY-R2`. Bản này chưa được nạp
và xác minh mở khóa trên bo thật: bo hiện ở xa, không có USB tại máy thực hiện.
Các giới hạn nguồn/reset và phục hồi của R1 tiếp tục áp dụng; xem
`docs/c5-recovery-r1/README.md`.
