# Ứng dụng Điều khiển MS51

Đây là ứng dụng Android native. Không dùng WebView và không mở giao diện web của ESP32 bên trong app.

## Luồng sử dụng

1. Mở app. Android sẽ yêu cầu điện thoại kết nối `MS51-PROGRAMMER` với mật khẩu `12345678`.
2. App kiểm tra ESP32 tại `192.168.4.1`, sau đó yêu cầu chọn Wi‑Fi 2.4 GHz và nhập mật khẩu để ESP32 kết nối Internet.
3. Khi ESP32 báo thành công, app hiện rõ IP LAN của thiết bị và yêu cầu điện thoại kết nối cùng Wi‑Fi đó.
4. Chỉ khi kiểm tra được IP LAN thành công, màn điều khiển native mới mở.

Sau khi vào app, các tab native gồm:

- `Vận hành`: mode, LED xanh/đỏ, trạng thái ba rơ-le, biến và log UART.
- `Cài đặt`: toàn bộ cấu hình runtime 217 byte giống giao diện web.
- `Nạp chương trình`: chọn/upload HEX hoặc BIN, nạp, verify, reset, đọc chip và lệnh sửa chữa có xác nhận.
- `Thiết bị`: xem Wi‑Fi/IP hiện tại và bắt đầu lại luồng đổi Wi‑Fi.

Android bắt buộc hiện hộp thoại hệ thống để xác nhận đổi Wi‑Fi; ứng dụng không thể tự ý đổi mạng im lặng. APK debug sau khi build ở `app/build/outputs/apk/debug/app-debug.apk`.
