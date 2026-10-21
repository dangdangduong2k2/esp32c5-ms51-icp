# MS51 Connect · ESP32-C5 / MS51

## MS51 3.4.1 · Trở lại SIG vật lý

MS51 luôn đọc chân SIG P0.1 như cũ; ngưỡng nhiệt độ động cơ không thay tín hiệu này. Giữ nguyên app/C5 3.4.0 và phần 50k điều khiển relay 3. [File HEX, phạm vi thay đổi và kiểm thử](docs/ms51-physical-sig-3.4.1/README.md). Bản này chỉ xuất file, chưa nạp lên bo.

## Bản thử nghiệm 3.4.0 · Ngưỡng ngắt và ngưỡng xả đá

[Tải APK 3.4.0](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.4.0/MS51-Connect-3.4.0.apk) · [Bản nạp, hành vi và kiểm thử](docs/ms51-connect-3.4.0/README.md)

Ngưỡng ngắt dùng cảm biến động cơ 10k/20k đang chọn. Ngưỡng xả đá dùng 50k, bật riêng relay 3 khi nhiệt độ thấp hơn ngưỡng, tắt khi bằng/cao hơn; cảm biến lỗi hoặc mất liên lạc thì trở về điều khiển theo mạch. Bấm **Lưu** để áp dụng cảm biến và hai ngưỡng. Cần cập nhật APK, C5 và MS51; giữ chân 10k IO5, 20k IO6, 50k IO4.

## Bản thử nghiệm 3.3.1 · 10k IO5, 20k IO6, 50k IO4

Đổi cách đấu mới: **NTC 10k → IO5, NTC 20k → IO6, NTC 50k → IO4**. Mỗi nhánh B3950, trở kéo lên 10k tới 3,3 V. Cài APK và nạp C5 3.3.1; giữ MS51/server hiện có. [Tải bản mới và hướng dẫn](docs/ms51-connect-3.3.1/README.md).

## Bản thử nghiệm 3.3.0 · Ba cảm biến và điều kiện kích

[Tải APK 3.3.0](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.3.0/MS51-Connect-3.3.0.apk) · [Đấu dây, bản nạp và preview](docs/ms51-connect-3.3.0/README.md)

Thêm NTC **20k ở IO5**, giữ **10k ở IO6**, **50k ở IO4**; tất cả B3950, mỗi nhánh dùng trở kéo lên 10k tới 3,3 V. Thẻ **Điều kiện kích** cho chọn 10k/20k/50k/SIG và nhập ngưỡng. Nhiệt độ lớn hơn ngưỡng thay tín hiệu SIG; chọn SIG tiếp tục đọc P0.1 active-low như cũ. Cấu hình lưu tại bo. Cần cập nhật APK, C5, MS51 và server; đã nạp/xác minh trên bo đang nối và triển khai server.

## Bản thử nghiệm 3.1.6 · Chỉ hiện nét LED đang sáng

[Tải APK 3.1.6](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.6/MS51-Connect-3.1.6.apk) · [Preview và kiểm thử](docs/ms51-connect-3.1.6/README.md)

Bỏ các thanh LED mờ bên trong chữ số; chỉ giữ nét đang sáng. Cài đè APK, không cần nạp lại C5/MS51 hoặc đổi máy chủ. Hai đầu đo và cách nhận biết ô đếm giữ như 3.1.5.

## Bản thử nghiệm 3.1.5 · Hai cảm biến và ô đếm gọn hơn

[Tải APK 3.1.5](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.5/MS51-Connect-3.1.5.apk) · [Bộ ba file C5](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.5/ESP32-C5-DAT1-CLK0-RS3-DUAL-NTC.zip) · [Preview, đấu dây và kiểm thử](docs/ms51-connect-3.1.5/README.md)

Bỏ nhãn xanh/đỏ và số 0 đầu (007 → 7); nhấp nháy ô có số giảm. Lúc mới mở cần đợi lần giảm đầu tiên vì MS51 chưa gửi trạng thái đếm trực tiếp. Nhiệt độ tách trái **10k ở IO6**, phải **50k ở IO4**, cả hai **B3950 và trở kéo lên 10k tới 3,3 V**. DAT1/CLK0/RS3 giữ nguyên. Cần APK/C5 mới để đọc hai đầu đo; không đổi VPS/MS51. Chưa thử cảm biến trên phần cứng ở lần này.

## Bản thử nghiệm 3.1.4 · Trở lại chân cũ, NTC ở IO6

[Tải APK 3.1.4](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.4/MS51-Connect-3.1.4.apk) · [Bộ ba file C5](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.4/ESP32-C5-DAT1-CLK0-RS3-NTC-IO6.zip) · [Hướng dẫn](docs/ms51-connect-3.1.4/README.md)

**DAT = IO1, CLK = IO0, RS = IO3; NTC = IO6.** Nguồn 3,3 V → trở 10 kΩ → IO6 → NTC 10 kΩ B3950 → GND. Cần APK và C5 mới, không cần đổi VPS hoặc nạp MS51. Giao diện giữ nguyên; app vẫn tương thích cảm biến IO1 của bản 3.1.3. Chưa kiểm tra nhiệt độ trên phần cứng ở lần này.

## Bản thử nghiệm 3.1.3 · Nhiệt độ NTC ở IO1

[Tải APK 3.1.3](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.3/MS51-Connect-3.1.3.apk) · [Bộ ba file C5](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.3/ESP32-C5-DAT2-CLK3-RS4-NTC-IO1.zip) · [Đấu dây, preview và kiểm thử](docs/ms51-connect-3.1.3/README.md)

Thêm thẻ **Nhiệt độ**: nguồn 3,3 V → điện trở 10 kΩ → IO1 → NTC 10 kΩ B3950 → GND. Dùng bản **DAT2/CLK3/RS4**, cài APK và nạp C5 mới; không cần đổi VPS hoặc nạp MS51. Có xử lý hở/chập cảm biến và mất dữ liệu. Đã build và kiểm thử giao diện, chưa đo thực tế trên mạch. Bản DAT1/CLK0/RS3 mặc định không bật cảm biến IO1.

## Bản thử nghiệm 3.1.2 · Giao diện gọn hơn

[Tải MS51 Connect 3.1.2 APK](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.2/MS51-Connect-3.1.2.apk)

Tổng quan chỉ giữ nút Làm mới, bỏ ba lối tắt phía sau và bảng Dữ liệu trực tiếp. Ba rơ-le có biểu tượng nguồn, màu xanh khi bật, xám khi tắt hoặc đang chờ dữ liệu; nhãn trạng thái phân biệt rõ từng trường hợp. Tab Firmware đổi thành Cập nhật; các thông báo kết nối và thông tin thiết bị dùng cách gọi dễ hiểu. Cài đè APK 3.1.1, không cần cập nhật máy chủ hoặc nạp lại chip.

## Bản thử nghiệm 3.1.1 · Nhật ký thời lượng MS51

[Tải MS51 Connect 3.1.1 APK](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.1.1/MS51-Connect-3.1.1.apk) · [Kiểm thử](docs/ms51-connect-3.1.1/test-results.json)

Nhật ký trong Tổng quan hiển thị thời lượng MS51 đã lưu ở L00–L29, thay các dòng trạng thái UART. Nguồn hiện tại có **30 vị trí**, mỗi đơn vị là 100 ms; app hiển thị phút/giây, giữ số L để đối chiếu với mạch và ẩn vị trí bằng 0. Đây là thời lượng, không phải ngày giờ xảy ra. Làm mới mỗi 15 giây khi mở Tổng quan; dữ liệu cũ được đánh dấu nếu mất kết nối. Cài đè 3.1.0, không cần ghép lại hay nạp firmware C5/MS51 đang dùng trên bo thật. Máy chủ phải có endpoint lịch sử mới. [Chi tiết giao thức](docs/ms51-connect-3.1.1/history.md).

Giữ tính năng 3.1.0: admin cấp quyền từng mục cài đặt và cấp tệp HEX riêng cho thiết bị; app chỉ hiện nút **Cập nhật** khi có bản được cấp. Mã nguồn đầy đủ ở `mobile/android`, `server`, `main` và `MS51_code`.

## Lịch sử bản thử nghiệm 3.0.2 · Tài khoản máy chủ & MQTT

[Tải MS51 Connect 3.0.2 APK](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.0.2/MS51-Connect-3.0.2.apk) · [Tệp phát hành](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/tag/v3.0.2)

**Bản vá 3.0.2:** mỗi yêu cầu tới máy chủ dùng mạng Internet hiện tại của Android, tự cập nhật trạng thái khi đổi Wi-Fi/4G/5G hoặc nối mạng trở lại. Lỗi Internet trên điện thoại được giải thích rõ; lệnh ghi không tự gửi lại khi mất phản hồi. Cài đè bản 3.0/3.0.1, giữ tài khoản và thiết bị đã ghép; **không cần nạp C5 hoặc ghép lại**. Đã thử chuyển mạng trên emulator với HTTPS công khai; vẫn cần xác nhận trên điện thoại thực tế. [Kiểm thử 3.0.2](docs/ms51-connect-3.0.2/test-results.json).

**Bản vá 3.0.1:** sửa bước tìm C5 sau khi chuyển điện thoại sang Wi-Fi nhà: dò theo MAC bằng NSD/mDNS, dự phòng dò trong mạng nội bộ khi mất IP, kiểm tra đúng MAC/SSID và giữ kết nối Internet bình thường của điện thoại. Cài đè APK 3.0, giữ tài khoản đã đăng nhập; **không cần nạp lại firmware 3.0**. [Kiểm thử bản vá](docs/ms51-connect-3.0.1/test-results.json).

- Tài khoản và danh sách nhiều thiết bị được lưu trên VPS; đăng nhập trên điện thoại khác vẫn thấy cùng danh sách.
- Điều khiển qua Internet bằng HTTPS → MQTT TLS → ESP32-C5. Sau lần ghép đầu tiên, điện thoại có thể dùng 4G/5G hoặc Wi-Fi khác.
- Giữ giao diện navy/xanh ngọc và luồng từng bước: tìm Wi-Fi thiết bị → quét → chọn mạng 2.4/5 GHz → nhập mật khẩu có con mắt → ghép tài khoản.
- AP theo MAC: `MS51-PROGRAMMER-<12 ký tự MAC>`. Mỗi bo có tài khoản MQTT và quyền theo topic riêng.
- Giữ các tab Tổng quan/Cài đặt/Firmware/Thiết bị, codec 217 byte, đọc chip, upload BIN/HEX, program/verify/reset và xác nhận `CONFIRM` cho thao tác xóa/ghi toàn bộ.

**Nâng cấp:** APK 3.0 cần firmware 3.0. Tài khoản cục bộ 2.2 không tự chuyển thành tài khoản máy chủ: đăng ký một lần và thêm lại thiết bị. Dữ liệu cục bộ cũ được giữ. APK thử nghiệm ký debug, package `vn.duong.ms51connect`; đừng cài nhầm APK `MS51-Control-v1.1.2.apk` cũ ở thư mục gốc.

| Thành phần | Mã nguồn / hướng dẫn |
|---|---|
| Android MS51 Connect | [mobile/android](mobile/android/README.md) |
| Tài khoản, phân quyền, MQTT trên VPS | [server](server/README.md) |
| Firmware mô phỏng trên ESP32-C5 | [firmware/esp32c5_simulator](firmware/esp32c5_simulator/README.md) |
| Firmware ICP cho MS51 thật | Project ESP-IDF ở thư mục gốc |
| Kiểm thử phiên bản 3.0 | [Báo cáo](docs/ms51-connect-3.0/test-results.json) |

Ở bản 3.0 lịch sử, MS51 được mô phỏng bằng C5 và chưa có NTC. Bản native 3.1.3 DAT2/CLK3/RS4 đã bổ sung NTC ở IO1 như mô tả phía trên. API Wi-Fi nội bộ vẫn dùng mô hình truy cập cũ; xem phạm vi bảo vệ và cách vận hành trong hướng dẫn server. Bản 2.2 và báo cáo lịch sử vẫn ở [release 2.2](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/tag/v2.2.0).

## Firmware nạp ICP gốc (ESP32-C5)

Bản chân **DAT = GPIO2, CLK = GPIO3, RS = GPIO4** có bộ ba file để nạp tại ESP Tool của Espressif. Xem [sơ đồ chân, địa chỉ nạp và cách build](docs/hardware/c5-dat2-clk3-rs4.md). Bản mặc định DAT1/CLK0/RS3 vẫn giữ nguyên.

Project ESP-IDF này dùng ESP32-C5 để nhận dạng, xóa, ghi và kiểm tra APROM của
MS51FC0AE qua ba chân nạp ICP:

| ESP32-C5 | MS51FC0AE TSSOP20 | Chức năng |
|---|---|---|
| GPIO0 | pin 18, `P0.2/ICE_CLK` | clock ICP / TX 3.3 khi chạy UART |
| GPIO1 | pin 8, `P1.6/ICE_DAT` | data ICP hai chiều / RX 3.3 khi chạy UART |
| GPIO3 | pin 4, `P2.0/nRESET` | reset/đi vào ICP / Rs 3.3 |
| GND | pin 7, `VSS` | mass chung |

Ánh xạ đã chốt theo [ảnh sơ đồ và kiểm tra RS ngày 2026-10-10](docs/hardware/README.md). GPIO4/5/6 dành cho AIN2/AIN3/AIN1 trên sơ đồ. Bản mô phỏng không điều khiển các chân target.

Nếu đã có `sdkconfig`, cập nhật cả `CONFIG_MS51_CLK_GPIO=0`, `CONFIG_MS51_DAT_GPIO=1`, `CONFIG_MS51_RST_GPIO=3` trong cấu hình đang build; `sdkconfig.defaults` không ghi đè giá trị đã lưu.

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
- GPIO0/GPIO1/GPIO3 là ánh xạ ICP mặc định trong project. Kiểm tra sơ đồ bo C5
  trước khi đấu MS51 thật, gồm chức năng chân và các mức cần giữ khi reset.

## Dùng trang web để nạp MS51

Sau khi ESP32-C5 khởi động, kết nối điện thoại hoặc máy tính vào:

- Wi-Fi: `MS51-PROGRAMMER-<MAC 12 ký tự>`
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
. 'C:\idf\v6.0\esp-idf\export.ps1'
idf.py set-target esp32c5
idf.py build
idf.py -p COMx flash monitor
```

Khi chưa có MS51, build và nạp project mô phỏng riêng trong
`firmware/esp32c5_simulator`; project ở thư mục gốc dành cho MS51 thật.
Bản mô phỏng không truy cập chân GPIO/UART của MS51.

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
I²C sang chính `P0.2=SCL` và `P1.6=SDA`; ESP vẫn có thể giữ sơ đồ GPIO0/GPIO1 ở
trên và giao tiếp địa chỉ 7-bit `0x60`. Tuy nhiên chip xuất xưởng mặc định không
có bootloader/LDROM hoạt động, nên vẫn cần Nu-Link nạp và cấu hình lần đầu.

Nguồn tham khảo:

- [MS51 32K Technical Reference Manual](https://www.nuvoton.com/resource-files/TRM_MS51_32KBFlash_Series_EN_Rev1.00.pdf)
- [MS51FC0AE product page](https://www.nuvoton.com/products/microcontrollers/8bit-8051-mcus/industrial-8051-series/ms51fc0ae/)
- [NuMicro-8051-prog](https://github.com/nikitalita/NuMicro-8051-prog)
- [ESP32-C5 datasheet](https://www.espressif.com/sites/default/files/documentation/esp32-c5_datasheet_en.pdf)
