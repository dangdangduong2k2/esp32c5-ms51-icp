# MS51 Connect 3.0

Android native Java, màu navy/xanh ngọc, `vn.duong.ms51connect`, versionCode 8, Android 7.0 trở lên. Tài khoản và danh sách thiết bị nằm trên VPS; app giữ phiên đăng nhập được mã hóa bằng Android Keystore. Mật khẩu tài khoản dùng Argon2id ở server. Không có khôi phục qua email trong bản này.

## Sử dụng

1. Cài APK 3.0, đăng ký tài khoản hoặc đăng nhập. Tài khoản cục bộ 2.2 cần đăng ký lại một lần; dữ liệu cũ không bị xóa.
2. Trang đầu hiển thị các thiết bị đã ghép, với trạng thái MQTT hiện tại. Tài khoản dùng được trên nhiều điện thoại.
3. Bấm **Thêm thiết bị** → **Tìm Wi-Fi thiết bị** → chọn AP `MS51-PROGRAMMER-<MAC>` → **Quét Wi-Fi** → chọn mạng → nhập mật khẩu. Có nút con mắt và hỗ trợ 5 GHz trên C5.
4. Bấm **Tiếp tục** để điện thoại vào mạng đích. App kiểm tra MAC/SSID rồi yêu cầu bo chứng minh kết nối MQTT trước khi ghi vào tài khoản.
5. Sau khi ghép, dùng Wi-Fi bất kỳ hoặc 4G/5G để mở bo. Giữ thẻ để đổi tên hoặc bỏ ghép; thay đổi áp dụng cho mọi điện thoại cùng tài khoản.

APK nhận biết firmware thiếu protocol 1 ngay ở bước kết nối bo, thay vì tiếp tục đến một API không có rồi báo HTTP 405. Luồng Wi-Fi giữ một hành động chính mỗi trang, khóa thao tác đang xử lý và bỏ phản hồi từ phiên cũ.

Trang chủ lấy danh sách từ HTTPS, không dò IP LAN. “Đã kết nối Internet” nghĩa là server nhận heartbeat MQTT mới; mất kết nối server trên điện thoại được hiển thị là chưa cập nhật được trạng thái. Đổi Wi-Fi của bo vẫn cần kết nối trực tiếp khi ở gần bo.

Giữ bốn tab Tổng quan/Cài đặt/Firmware/Thiết bị, codec 217 byte, đọc chip, upload/program/verify/reset, `CONFIRM` khi xóa hoặc ghi toàn bộ. Đích đến và phiên tài khoản được chốt trước khi xếp hàng. MQTT có mã lệnh/thời hạn, server giới hạn một yêu cầu đang xử lý mỗi bo. Nếu mất phản hồi sau một lệnh, hãy đọc trạng thái trước khi gửi lại.

## Build

JDK 17, Android SDK 35, Gradle wrapper:

    gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug

APK ký debug bằng khóa thử nghiệm hiện có. Không đặt token, mật khẩu VPS hoặc tài khoản MQTT chung trong app. Địa chỉ HTTPS được cấu hình tại `CloudApi.BASE` và khớp firmware/server.

## Kiểm thử

`CloudIntegrationTestRunner` là bài kiểm thử **live được gọi rõ ràng**: tạo tài khoản tạm trên VPS, ghép C5 thật, đọc/ghi cấu hình, kiểm tra truyền tệp và program/verify trên MS51 mô phỏng. Chỉ chạy trên emulator/bo thử nghiệm; nó thay tệp firmware đang lưu trên C5 bằng fixture kiểm thử và bỏ ghép khi xong.

    python tools/cloud_hardware_gateway.py
    adb -s emulator-5556 shell am instrument -w -e live true vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner

Lệnh Python chạy từ gốc repository, cần pyserial và C5 ở COM49 (sửa cổng khi cần). Adapter localhost:80 chỉ chuyển bước xác nhận LAN/ghép nối qua USB vì emulator không tham gia được AP vật lý; điều khiển từ xa đi qua HTTPS công khai và MQTT thật. Không xem bài này là kiểm thử radio provisioning trên điện thoại thật. Runner không chạy thao tác live nếu thiếu `-e live true`. Các fixture/runner AccountsDevices cũ là kiểm thử lịch sử của 2.2.

Vẫn cần thử quyền/chọn Wi-Fi của Android trên điện thoại thật và nhiều bo vật lý. NTC 10k chưa được hỗ trợ; dữ liệu MS51 trên C5 là mô phỏng.
