# MS51 Connect 3.1.4 — chân cũ, NTC ở IO6

Khôi phục DAT = IO1, CLK = IO0, RS = IO3. Đọc NTC tại IO6 (ADC1 channel 5).

Đấu: **3,3 V → điện trở 10 kΩ → IO6 → NTC 10 kΩ B3950 → GND**. Dùng chung mass. IO1 dành lại cho DAT, không nối NTC vào IO1 trên bản này.

Cài APK 3.1.4 và nạp C5 mới. App 3.1.3 chỉ nhận trường gpio = 1; app 3.1.4 nhận cả 1 và 6 để tương thích bản đã phát hành. Mô hình nhiệt độ, hiệu chuẩn ADC, lấy trung bình và thông báo lỗi cảm biến giữ nguyên như 3.1.3. Không cần đổi máy chủ hoặc nạp lại MS51.

## Ba file nạp C5

Giải nén `ESP32-C5-DAT1-CLK0-RS3-NTC-IO6.zip`, dùng https://espressif.github.io/esptool-js/ trên Chrome/Edge.

| Địa chỉ | File |
|---|---|
| `0x2000` | `bootloader.bin` |
| `0x8000` | `partition-table.bin` |
| `0x10000` | `esp32c5-dat1-clk0-rs3-ntc-io6.bin` |

DIO / 80 MHz / 4 MB, C5 revision 1.x, flash từ 4 MB. Không chọn Erase Flash để giữ cấu hình Wi-Fi và tài khoản; bấm RESET sau khi nạp. Nếu không tự kết nối được, giữ BOOT, bấm/thả RESET rồi thả BOOT và kết nối lại.

## Build

```powershell
.\tools\build-c5-dat1-clk0-rs3-ntc-io6.ps1 -WorkRoot 'D:\Codex\Documents\work\c5-io6'
```

Cấu hình mới mặc định bật IO6; sdkconfig cũ đã tồn tại cần bật `CONFIG_MS51_ANALOG_IO6=y` hoặc dùng script trên. Kconfig và kiểm tra biên dịch ngăn ADC trùng chân giao tiếp. Script DAT2/CLK3/RS4 vẫn có thể build bản IO1 cũ, tắt IO6 rõ ràng.

Kiểm thử: xem [báo cáo](test-results.json). Chưa nạp thử/đối chiếu nhiệt độ trên phần cứng ở lần này.
