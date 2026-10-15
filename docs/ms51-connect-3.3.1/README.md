# MS51 Connect 3.3.1 — Đổi chân hai cảm biến

| Cảm biến | Chân C5 | Kênh ADC1 |
| --- | --- | --- |
| NTC 10k B3950 | **IO5** | 4 |
| NTC 20k B3950 | **IO6** | 5 |
| NTC 50k B3950 | **IO4** | 3 |

Mỗi nhánh dùng **3,3 V → trở 10k → chân IO → NTC → GND**. DAT=IO1, CLK=IO0, RS=IO3 giữ nguyên.

Cần cài APK 3.3.1 và nạp bản C5 đi kèm để đọc đúng cách đấu mới. APK vẫn nhận cách đấu cũ 10k/IO6, 20k/IO5; không tráo giá trị giữa các thẻ. Phép tính nhiệt độ, chọn nguồn kích và so ngưỡng giữ nguyên. Không đổi server hoặc MS51; chức năng chọn nguồn kích cần MS51 hỗ trợ từ bản 3.3.0.

- [APK 3.3.1](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.3.1/MS51-Connect-3.3.1.apk)
- [Gói ba file C5](https://github.com/dangdangduong2k2/esp32c5-ms51-icp/releases/download/v3.3.1/ESP32-C5-NTC10K-IO5-20K-IO6-50K-IO4-3.3.1.zip)

Nạp web Espressif: bootloader `0x2000`, partition `0x8000`, app `0x10000`; DIO, 80 MHz, 4 MB. Không xóa toàn bộ flash khi cần giữ ghép nối/Wi-Fi. Gói dành cho C5 có flash từ 4 MB.

Build mới bật `CONFIG_MS51_ANALOG_IO5`, `CONFIG_MS51_NTC_20K_IO6`, `CONFIG_MS51_NTC_50K_IO4`; tắt `CONFIG_MS51_ANALOG_IO6` và `CONFIG_MS51_NTC_20K_IO5`. Các lựa chọn cũ được giữ để build bo đã đấu theo bản trước. Repo mặc định flash 16 MB; gói web dùng flash 4 MB với cùng vị trí các phân vùng.

[Kết quả build và kiểm thử](test-results.json). Bản này chưa nạp/đo trên phần cứng trong lần thay đổi này.
