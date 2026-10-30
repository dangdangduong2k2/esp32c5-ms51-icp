# Hẹn Giờ Lạnh 3.7.0 — Bluetooth không cần Internet

**Gói C5 mới: `ESP32-C5-Bluetooth-3.7.0-r2.zip`. APK vẫn là 3.7.0.**

Bản r2 giảm RAM dành cho tối ưu tốc độ Wi-Fi và các hàng đợi Bluetooth, chỉ cấp phát bộ đệm 36 KiB khi nhận firmware rồi giải phóng. Không giữ bộ đệm firmware cố định khi TLS cần bắt tay. Lỗi thiếu RAM cũng được báo đúng, thay vì báo nhầm file HEX hỏng. USB `debug` bổ sung thông tin RAM và IP để chẩn đoán.

Trước khi người dùng rút bo, đã thấy trao đổi BLE và MS51 đã báo xác minh đủ 28.942 byte cùng tín hiệu `local_settings=1`. Tuy nhiên bản C5 đang trên bo khi rút vẫn gặp thiếu RAM khi kết nối máy chủ. **C5 r2 được build và kiểm thử phần mềm sau khi bo đã rút, chưa được nạp hoặc xác nhận đồng thời Wi-Fi/BLE/TLS trên mạch thật. Lần tới cần nạp lại C5 r2.** Không cần cài lại APK nếu đã có 3.7.0.

Bản thử gồm APK, C5 và MS51. App thêm thiết bị qua BLE; không cần đăng nhập, Wi-Fi hay máy chủ để dùng chế độ gần. Khi chọn **Thêm Wi-Fi và liên kết tài khoản**, điện thoại gửi cấu hình Wi-Fi qua BLE, đăng nhập và ghép tài khoản với VPS hiện tại. Sau đó các chức năng bổ sung vẫn do quyền tài khoản trên máy chủ quyết định.

## Cách dùng

1. Cài đè APK. Cập nhật C5 và MS51 bằng các tệp trong bản phát hành.
2. Chọn **Dùng Bluetooth · Không cần Internet** ở màn hình đăng nhập, hoặc **Thêm thiết bị / Bluetooth · Điều khiển gần** trong danh sách.
3. Cấp quyền Thiết bị ở gần (Android cũ cần quyền Vị trí), bật Bluetooth, chọn `HenGio-xxxxxx`, xác nhận ghép Bluetooth nếu Android hỏi.
4. Chọn **Dùng không Internet** để xem trạng thái, chuyển Làm lạnh/Xả đá, xem nhiệt độ, nhật ký, mở khóa bằng mật khẩu của mạch và sửa nhóm cài đặt cơ bản.
5. Muốn dùng từ xa: vào **Thiết bị → Thêm Wi-Fi và liên kết tài khoản**. Điện thoại cần Internet để đăng nhập/ghép tài khoản; không cần đổi sang Wi-Fi do C5 phát. Sau khi ghép thành công, mở thiết bị từ danh sách tài khoản để dùng quyền online.

Bluetooth dùng một điện thoại mỗi lúc. Mất kết nối sẽ báo lỗi; lệnh ghi không tự gửi lại. Kết nối gần không dùng phiên đăng nhập hay quyền lưu đệm từ máy chủ. Nhiệt độ là thông tin xem; hai ngưỡng và cập nhật HEX thuộc luồng online.

## Cài đặt offline

Luôn có: Làm lạnh CL/OP, Xả đá CL/OP, ST LED, ST LCD, Bắt đầu làm lạnh, Bắt đầu xả đá, DF, END, HDF.

HCF và Kiểu màn hình đọc theo cờ hiện menu trên MS51 (`0x2A`, `0x29`), không suy từ giá trị đang chọn H/CF hoặc LCD/LED. Không được sửa cờ hiện menu, mật khẩu, giới hạn thời gian, cực tính relay hay các mục quản trị bằng BLE.

MS51 có lệnh mới `0x58` để lưu nhóm cơ bản. Nó kiểm tra giới hạn, từ chối khi đang bị khóa mật khẩu, bỏ qua mọi byte ngoài nhóm cho phép và giữ nguyên mật khẩu/bộ đếm bảo vệ. Chỉ ghi EEPROM sau khi tất cả giá trị hợp lệ, rồi khởi động lại như khi lưu cài đặt thông thường. Chu trình đang chạy khởi động lại khi lưu; bộ đếm bảo vệ không bị đặt về 0 bởi lệnh này.

MS51 cũ vẫn đọc được trạng thái/cài đặt qua C5 mới, nhưng nút Lưu offline bị khóa cho đến khi có tín hiệu `local_settings=1`. Không thử lại bằng lệnh ghi cũ vì lệnh cũ xóa trạng thái bộ đếm PIN.

## Nạp C5

Gói C5 hiện dành cho bo ESP32-C5 flash 16 MB đã dùng trong dự án, DAT=IO1, CLK=IO0, RS=IO3; NTC 10k=IO5, 20k=IO6, 50k=IO4.

| File | Địa chỉ |
|---|---|
| bootloader.bin | `0x2000` |
| partition-table.bin | `0x8000` |
| esp32c5_ms51_icp.bin | `0x10000` |

Nạp cả ba file, không chọn Erase All. Vùng NVS Wi-Fi/ghép nối ở `0x9000` không bị ghi. Partition app tăng lên 2 MiB để chứa BLE, cache HEX chuyển từ `0x190000` sang `0x210000`. File HEX từng tải vào cache cũ cần được tải lại; chương trình và EEPROM trên MS51 không bị thay bởi việc đổi partition C5. Không dùng riêng file app với bảng partition cũ.

Nạp MS51 bằng **program thường**, không dùng erase/program-full. HEX mới không chứa trang EEPROM `0x7F00–0x7FFF`. Project Keil đã đồng bộ giới hạn code `0x0000–0x7EFF` với cấu hình APROM 32 KB và EEPROM `0x7F00` đang dùng.

## Giao thức BLE

- Service: `51b10001-8c22-4f42-9a51-3844be1fe4e0`.
- RX (write có phản hồi, yêu cầu mã hóa): `51b10002-8c22-4f42-9a51-3844be1fe4e0`.
- TX (indication): `51b10003-8c22-4f42-9a51-3844be1fe4e0`.
- Ghép/bond bằng Android, lưu khóa vào NVS; Secure Connections / Just Works, không xác nhận danh tính bằng mã số trên mạch.
- UTF-8 JSON kết thúc bằng newline, phân mảnh theo MTU. Request `{id,method,path,body}`, response `{id,result}`. Tối đa request 8191 byte, response 32768 byte; chỉ một thao tác chờ phản hồi trên mỗi kết nối.
- C5 kiểm soát danh sách route và trường được ghi. `/api/ble/settings` nhận `values`; không nhận ảnh EEPROM từ điện thoại. GET cấu hình che vùng mật khẩu/PIN. Wi-Fi và claim tài khoản dùng route riêng đã có; quyền online vẫn kiểm tra tại VPS.
- UART `0x58` dùng framing/CRC/sequence hiện có và 217 byte payload; MS51 chỉ áp dụng các byte cơ bản và giữ trạng thái riêng tại thời điểm ghi.

## Kiểm thử

- ESP-IDF 6.0 build C5; Keil build MS51 không lỗi/cảnh báo.
- Native C chạy chính policy C5: byte order, trường được phép, từ chối giá trị xấu không ghi một phần, cờ hỗ trợ hết hạn, che PIN, chặn cập nhật/ghi raw/thermal qua BLE.
- Native C chạy chính hàm MS51: giữ mật khẩu và bộ đếm, khóa PIN, giới hạn min/max, cờ menu và ghi EEPROM một lần.
- Parser BIN/HEX chạy trong ngân sách heap 36 KiB, kiểm tra thiếu bộ nhớ, phục hồi sau lỗi, đọc phân mảnh và không giữ bộ đệm sau khi xong.
- Android build/lint; kiểm thử BLE trên giả lập cho phân mảnh UTF-8, MTU 23, giới hạn khung, định danh thiết bị, snapshot, cài đặt không tài khoản, quyền theo mạch và lưu phần thay đổi.
- Kiểm thử hồi quy quản lý cài đặt, cuộn, mở khóa và quyền công tắc vật lý.
- Còn cần kiểm tra trên C5 r2: kết nối lại bằng điện thoại, lưu offline sau khi mở khóa, đọc lại cấu hình, thêm Wi-Fi/tài khoản và tải HEX khi cùng dùng BLE. Kiểm thử giả lập không thay thế kiểm thử này.
