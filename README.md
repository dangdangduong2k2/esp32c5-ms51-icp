# ESP32-C5 programmer for MS51FC0AE

Project ESP-IDF này dùng ESP32-C5 để nhận dạng, xóa, ghi và kiểm tra APROM của
MS51FC0AE qua ba chân nạp ICP:

| ESP32-C5 | MS51FC0AE TSSOP20 | Chức năng |
|---|---|---|
| GPIO2 | pin 4, `P2.0/nRESET` | reset/đi vào ICP |
| GPIO3 | pin 18, `P0.2/ICE_CLK` | clock ICP |
| GPIO4 | pin 8, `P1.6/ICE_DAT` | data ICP hai chiều |
| GND | pin 7, `VSS` | mass chung |

## Điều phải lưu ý trước khi đấu dây

`ICE_DAT/ICE_CLK` **không phải giao thức I²C chuẩn**. Đây là giao thức ICP riêng
của Nuvoton, chỉ có hình thức clock/data hai dây giống I²C. Vì vậy project dùng
GPIO bit-bang và không dùng peripheral I²C của ESP32.

- Nên cấp MS51 bằng **3,3 V** khi nối trực tiếp. GPIO ESP32-C5 không chịu được
  tín hiệu 5 V. Nếu MS51 chạy 5 V, phải dùng mạch chuyển mức phù hợp cho DAT hai
  chiều và cho cả CLK/RST; không nối thẳng.
- Nối GND của hai mạch với nhau.
- Theo datasheet Nuvoton, có thể dùng pull-up 100 kΩ tại ICE_DAT/ICE_CLK và điện
  trở nối tiếp 100 Ω để lọc nhiễu. nRESET nên có pull-up ngoài; tải hoặc tụ quá
  lớn trên ba đường ICP có thể làm chuỗi vào chế độ nạp thất bại.
- GPIO2 và GPIO3 là strapping pins của ESP32-C5; GPIO2/3/4 cũng là pad JTAG.
  Mạch ngoài không được ép sai mức lúc ESP32 reset. USB Serial/JTAG vẫn dùng
  GPIO13/14 và được chọn làm console mặc định.

## Dùng trang web để nạp MS51

Sau khi ESP32-C5 khởi động, kết nối điện thoại hoặc máy tính vào:

- Wi-Fi: `MS51-PROGRAMMER`
- Mật khẩu: `12345678`
- Trang nạp: `http://192.168.4.1`

Chọn file APROM dạng `.bin` (tối đa 32 KB) hoặc Intel HEX (`.hex`, `.ihex`, `.ihx`,
tối đa 96 KB), bấm **Tải vào ESP32**, sau đó bấm **Nạp firmware**. Với HEX, ESP32
kiểm checksum, EOF và địa chỉ APROM `0x0000`–`0x7FFF`; các khoảng trống trong file
được giữ nguyên khi nạp thường. Nút nạp mặc định chỉ thay các byte có trong image và
giữ nguyên phần APROM còn lại. Trang web cũng có các nút đọc thông tin chip, verify và reset.

Firmware upload được lưu trong hai slot A/B riêng trên flash ESP32. File mới chỉ trở thành
bản đang dùng sau khi đã ghi xong và kiểm CRC, vì vậy mất điện giữa lúc upload không ghi đè
bản hợp lệ trước đó. Hai thao tác **Nạp toàn bộ APROM** và **Xóa toàn bộ chip** có thể phá
hủy dữ liệu nên phải nhập đúng `CONFIRM` trên hộp thoại.

> Lưu ý khi nâng cấp từ bản chỉ hỗ trợ BIN: bố cục vùng lưu firmware đã thay đổi để lưu
> bitmap của Intel HEX, vì vậy hãy tải lại file firmware vào ESP32 một lần.

Mật khẩu trên là cấu hình riêng của bản build này. Có thể đổi SSID, mật khẩu và kênh tại
`idf.py menuconfig` → **MS51FC0AE ICP programmer** trước khi triển khai sang thiết bị khác.

## Chuẩn bị firmware MS51

Không cần chép file vào project nếu dùng trang web. Muốn có thêm một image dự phòng được
nhúng sẵn trong firmware ESP32, đặt file nhị phân APROM tại:

```text
firmware/ms51_app.bin
```

File phải là binary thô bắt đầu từ địa chỉ APROM `0x0000`; vùng trống phải có
giá trị `0xFF`. Không đưa file Intel HEX dạng text trực tiếp vào đây. Nếu CONFIG
đang dành 4/3/2/1 KB cho LDROM thì kích thước APROM khả dụng tương ứng là
28/29/30/31 KB; project đọc CONFIG thực tế và từ chối image quá lớn.

## Build và nạp ESP32-C5

Project cần ESP-IDF 6.0 hoặc mới hơn có target `esp32c5`:

```powershell
$env:IDF_TOOLS_PATH='C:\Espressif'
. 'C:\idfsetup\v6.0\esp-idf\export.ps1'
idf.py build
idf.py -p COMx flash monitor
```

Mặc định project **không tự nạp khi khởi động**. Có thể dùng ngay trang web ở trên; hoặc sau
khi mở monitor, dùng lệnh
`program` để:

1. vào ICP và kiểm tra đúng PDID `0x0B005332` của MS51FC0AE;
2. đọc CONFIG để xác định khóa bảo mật và biên APROM/LDROM;
3. so sánh từng page 128 byte;
4. chỉ xóa/ghi những page phủ bởi image và khác nội dung hiện tại, rồi verify lại;
5. thoát ICP và nhả nRESET để MS51 chạy.

`program` giữ nguyên mọi byte không có trong image, kể cả phần còn lại của page cuối,
vùng ứng dụng dùng làm Data Flash và các khoảng trống trong Intel HEX. Chỉ dùng
`program-full CONFIRM` nếu muốn coi image là **toàn bộ APROM** và xóa về `0xFF` mọi
byte không có trong image (bao gồm các khoảng trống của HEX).

Không có image nhúng thì project vẫn build, phát Wi-Fi và mở console. Có thể chủ động bật
auto-program an toàn (chỉ phạm vi image), hoặc đổi chân/timing bằng
`idf.py menuconfig` → **MS51FC0AE ICP programmer**.

## Console

Gõ `help` để xem lệnh:

- `info` — đọc PDID, CID, CONFIG, kích thước APROM/LDROM và trạng thái khóa;
- `image` — xem kích thước/CRC32 của image upload qua web hoặc image dự phòng đã nhúng;
- `program` — nạp đúng phạm vi image và giữ nguyên dữ liệu APROM phía sau;
- `program-full CONFIRM` — nạp image rồi xóa về `0xFF` mọi byte APROM không có trong image;
- `verify` — so sánh đúng phạm vi image với APROM;
- `reset` — reset MS51 rồi nhả chân;
- `erase CONFIRM` — whole-chip erase, xóa APROM, LDROM và CONFIG, sau đó đọc lại
  toàn bộ flash để xác minh. Lệnh này phá hủy dữ liệu và yêu cầu xác nhận rõ ràng.

Nếu chip đã bật security lock, code cố ý không tự mass-erase. Phải chạy
`erase CONFIRM`, sau đó `program`. Whole-chip erase đưa CONFIG về `0xFF`, tức
mặc định không còn LDROM.

## Giới hạn quan trọng

Nuvoton công khai việc ICP dùng nRESET/DAT/CLK nhưng không công khai đặc tả
wire-level; ICPTool/Nu-Link là công cụ proprietary. Phần transport trực tiếp ở
đây dựa trên implementation MIT mã nguồn mở đã được báo cáo thử với MS51FC0AE,
không phải SDK chính thức của Nuvoton. Project đã được build/kiểm tra tĩnh nhưng
không thể kiểm thử điện với chip MS51 trong môi trường này. Nếu cần quy trình
sản xuất được Nuvoton hỗ trợ chính thức, dùng Nu-Link; hoặc provision bootloader
ISP-I²C vào LDROM một lần rồi dùng giao thức ISP công khai.

Với phương án ISP-I²C chính thức, bootloader MS51 phải bật `I2CPX=1` để remap
I²C sang chính `P0.2=SCL` và `P1.6=SDA`; ESP vẫn có thể giữ sơ đồ IO3/IO4 ở
trên và giao tiếp địa chỉ 7-bit `0x60`. Tuy nhiên chip xuất xưởng mặc định không
có bootloader/LDROM hoạt động, nên vẫn cần Nu-Link nạp và cấu hình lần đầu.

Nguồn tham khảo:

- [MS51 32K Technical Reference Manual](https://www.nuvoton.com/resource-files/TRM_MS51_32KBFlash_Series_EN_Rev1.00.pdf)
- [MS51FC0AE product page](https://www.nuvoton.com/products/microcontrollers/8bit-8051-mcus/industrial-8051-series/ms51fc0ae/)
- [NuMicro-8051-prog](https://github.com/nikitalita/NuMicro-8051-prog)
- [ESP32-C5 datasheet](https://documentation.espressif.com/esp32-c5_datasheet_en.html)
