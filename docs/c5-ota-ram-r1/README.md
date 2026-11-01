# C5 OTA RAM R1 — nhận HEX với bộ đệm 4 KiB

Sửa lỗi `ESP khong du bo nho nhan tep.` khi C5 nhận firmware MS51 qua máy chủ.
Bo chuẩn được kiểm tra còn 54.672 byte RAM trống nhưng khối liền nhau lớn nhất
chỉ 24.576 byte, không đáp ứng bộ đệm canonical 36 KiB trước đây.

HTTP upload nội bộ và tải HTTPS từ máy chủ dùng chung đường xử lý mới:

- Giữ bản đồ địa chỉ 4 KiB trong RAM, kiểm tra từng bản ghi HEX trước khi ghi.
- Ghi vào slot flash dự phòng đã xóa; giữ nguyên slot đang sử dụng.
- Chỉ kích hoạt sau khi kiểm tra toàn bộ file, EOF, checksum, địa chỉ trùng/vượt
  giới hạn, coverage và CRC. Header/commit marker được ghi cuối.
- Không thay bảng phân vùng, giao thức cloud, dữ liệu MS51 hay app Android.

## Bản xuất

`ESP32-C5-STANDARD-OTA-RAM-R1.zip`: **bo chuẩn 16 MB**, không phải test IO1/N4.
DAT IO1, CLK IO0, RS IO3; NTC 10k IO5, 20k IO6, 50k IO4.
Giữ BLE, Wi-Fi, VPS VinaHost và bản vá reset USB C5 rev1.0 hiện có.
Không tự nạp MS51 khi khởi động.

Ba file: bootloader tại `0x2000`, partition-table tại `0x8000`, app tại `0x10000`.
Bo đã dùng đúng bảng phân vùng chỉ cần app. Không Erase All.
Build ESP-IDF 6.0, project version `C5-OTA-RAM-R1`, app 0x1a36a0 byte;
partition app 0x200000 byte. Gói xuất kèm sdkconfig và patch SDK để tái tạo build.

## Xác minh

- Parser cũ: BIN/HEX 32 KiB, chia đoạn, file hỏng, thiếu bộ nhớ và phục hồi.
- Storage mới: cấp thêm tối đa 4 KiB; so sánh toàn bộ canonical payload/coverage
  với parser cũ bằng chính file `MS51-Bluetooth-3.7.4.hex` (84.552 byte).
- Giả lập flash NOR: mất kết nối, ghi dở ở từng bước data/coverage/header/commit,
  sai checksum, thiếu EOF, địa chỉ trùng/vượt giới hạn, không đủ RAM, đang mmap.
  Khởi động lại vẫn đọc được slot cũ sau các lần lỗi.
- Bo chuẩn: nạp và xác minh app, đối chiếu NVS trước/sau giống nhau; chương trình
  trả lời trở lại sau reset. Không nạp lại MS51.
- Upload LAN file HEX thành công khoảng 1,5 giây, CRC 820705851, 29.159 byte
  có dữ liệu. Upload sai checksum bị từ chối, bản trước được giữ nguyên.
- Cấu hình MS51 đọc trước/sau giống nhau. Chưa chạy lại toàn bộ quy trình cloud
  OTA có bước ghi MS51 trong lần kiểm tra này.

Job OTA cũ đã bị đánh dấu không xác định trên server: admin cần cấp lại bản HEX
để tạo lượt cập nhật mới, không tự phát lại job đã lỗi.

## Host tests

Từ thư mục repo, dùng GCC/Clang (hoặc `zig cc`), đặt output trong thư mục làm việc D:

```powershell
cc -std=c11 -Wall -Wextra -Werror -I tests/image -I main tests/image/stream_test.c main/ms51_image.c -o D:/work/stream_test.exe
cc -std=c11 -Wall -Wextra -Werror -I tests/image -I main tests/image/storage_stream_test.c main/ms51_image.c -o D:/work/storage_stream_test.exe
D:/work/stream_test.exe
D:/work/storage_stream_test.exe D:/firmware/MS51-Bluetooth-3.7.4.hex
```

Các stub ESP/FreeRTOS trong `tests/image` chỉ phục vụ host test, không được dùng
trong build firmware.
