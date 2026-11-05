# C5 Standard Recovery R4 — đồng bộ giờ cloud

## Phạm vi

Bản chuẩn: DAT IO1, CLK IO0, RS IO3; NTC 10k IO5, 20k IO6, 50k IO4.
Flash cấu hình 4 MB, DIO, 80 MHz; dùng cho chip flash từ 4 MB trở lên.
Giữ bộ nhận HEX ít RAM và phục hồi của R3, bảng phân vùng, NVS, BLE và Wi-Fi.
App Android vẫn dùng 3.7.15. Không tự xóa hoặc nạp MS51.

## Lỗi và thay đổi

R3 chỉ bắt đầu SNTP khi `time(NULL)` chưa vượt mốc đầu năm 2026. Nếu RTC giữ lại
một giá trị có vẻ hợp lệ nhưng bị lệch, SNTP không bắt đầu trong lần boot đó.
Lệnh cloud có thể bị từ chối do thời gian dù MQTT/TLS đã kết nối được.

R4:

- Bắt đầu SNTP sau khi Wi-Fi có IP, kể cả khi RTC đã có giá trị hợp lệ.
- Sau khi mất IP và có lại IP, bắt đầu đồng bộ lại.
- Giữ SNTP chạy định kỳ mỗi phút.
- Khi thời gian của lệnh không khớp, yêu cầu SNTP đồng bộ lại với giới hạn thử
  lại tối thiểu 15 giây. Lệnh bị từ chối vẫn không được thực hiện tự động.
- Giữ giới hạn thời hạn lệnh 60 giây và dung sai giờ phát lệnh 5 giây; không nới
  kiểm tra thời gian hoặc kiểm tra ngày chứng chỉ TLS. Từ chối thêm thời hạn bằng
  0, âm hoặc số không hữu hạn.
- `/api/status` bổ sung `clock.unix_time`, `clock.sntp_synced`, `clock.sync_age_ms`.
  Đồng hồ hệ thống có năm hợp lệ và việc đã đồng bộ SNTP là hai thông tin riêng.
- Không đổi quy tắc chống gửi lặp lệnh hoặc tự nạp lại MS51.

## Nạp

Gói `ESP32-C5-STANDARD-RECOVERY-R4.zip`:

| Địa chỉ | File |
|---|---|
| 0x2000 | bootloader.bin |
| 0x8000 | partition-table.bin |
| 0x10000 | ESP32-C5-STANDARD-RECOVERY-R4-app.bin |

Bo đã nạp đủ R3 đúng bảng phân vùng chỉ cần thay file app ở 0x10000.
Không chọn Erase All. Không nạp C5 khi MS51 đang có tác vụ phục hồi pending.
Khởi động bình thường, không giữ BOOT. Version cần đọc sau nạp là
`C5-STD-RECOVERY-R4`; pins vẫn là 1/0/3.

## Kiểm chứng

- Build ESP-IDF 6.0 với cấu hình chân chuẩn và flash 4 MB.
- Host test dùng chính hàm kiểm tra thời gian/policy đồng bộ: RTC giữ lại nhưng
  chưa bắt đầu SNTP, thử lại sau lỗi, giới hạn resync, lệnh hợp lệ, hết hạn,
  từ tương lai, thời hạn quá dài/âm/bằng 0, NaN/Infinity và RTC chưa đồng bộ.
- Đóng gói kiểm tra version, header flash, địa chỉ phân vùng, NVS và SHA256.
- R3 đã nhận HEX qua HTTPS trên bo thật trong 5,41 giây, CRC đúng và cấu hình
  MS51 trước/sau giống nhau. Chưa ghi chương trình MS51 trong lần thử đó.
- Sau RESET, R3 mất kết nối cloud một thời gian trong khi BLE vẫn dò được;
  sau đó trở lại và nhận lệnh với timestamp bình thường. Thử nhận HEX lần nữa
  thành công trong 5,55 giây, CRC đúng, cấu hình giữ nguyên, chưa ghi MS51.
- R4 chưa được nạp và kiểm tra trên bo ở xa. Chưa đo được trạng thái Wi-Fi/SNTP
  của R3 trong lúc offline, nên không khẳng định lỗi giờ là nguyên nhân duy nhất
  của khoảng mất Internet sau reset.

SNTP vẫn cần mạng truy cập được máy chủ thời gian. Bản sửa không thay thế việc
kiểm tra Wi-Fi, DNS hoặc UDP bị chặn. Các giới hạn điện và phục hồi của R1/R2
tiếp tục áp dụng.

```powershell
cc -std=c11 -Wall -Wextra -Werror -I main tests/programmer/cloud_time_test.c -o D:/work/cloud_time_test.exe
D:/work/cloud_time_test.exe
```
