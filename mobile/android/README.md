# MS51 Connect 3.0.2

Android native Java, màu navy/xanh ngọc, `vn.duong.ms51connect`, versionCode 10, Android 7.0 trở lên. Tài khoản và danh sách thiết bị nằm trên VPS; app giữ phiên đăng nhập được mã hóa bằng Android Keystore. Mật khẩu tài khoản dùng Argon2id ở server. Không có khôi phục qua email trong bản này.

3.0.2 mở HTTPS qua `ConnectivityManager.getActiveNetwork()` và `Network.openConnection()` ở **từng yêu cầu**, kể cả khi dùng lại phiên tài khoản sau đổi mạng. DNS/socket đi theo mạng hiện tại, độc lập với kết nối AP thiết lập. `registerDefaultNetworkCallback` làm mới các lần đọc ở trang chủ/tổng quan khi mạng thay đổi; phản hồi cũ không ghi đè trạng thái mới. Không tự gửi lại lệnh ghi khi mất phản hồi, không hạ xác thực TLS. Lỗi mất Internet trên điện thoại được hiển thị riêng với trạng thái thiết bị. [Android Network.openConnection](https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL)) · [Theo dõi mạng mặc định](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).

3.0.1 sửa lỗi chưa tìm thấy C5 sau khi điện thoại đã vào Wi-Fi nhà. App dùng IP đã biết và Android NSD/mDNS theo MAC; nếu mất IP do C5 đổi băng tần, app dò HTTP trong tối đa một /24 thuộc Wi-Fi hiện tại (8 kết nối đồng thời, giới hạn 35 giây). Chỉ đọc `/api/status`; xác nhận cả MAC lẫn SSID trước khi gửi cấu hình ghép. Không dò mạng công cộng. Dừng tìm khi hủy/đổi bước. Mạng khách chặn thiết bị nội bộ vẫn cần chuyển sang mạng cho phép các thiết bị liên lạc.

Wi-Fi nhà dùng kết nối Internet thông thường của Android/cài đặt Wi-Fi hệ thống, thay vì `WifiNetworkSpecifier` dành cho kết nối nội bộ. HTTP tới bo được gắn với `Network` đã chọn; app không gắn toàn bộ tiến trình vào mạng AP. [Android Wi-Fi Network Request](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap) · [Android NSD](https://developer.android.com/reference/android/net/nsd/NsdManager).

## Sử dụng

1. Cài APK 3.0.2, đăng ký tài khoản hoặc đăng nhập. Cài đè bản 3.0/3.0.1 giữ tài khoản và thiết bị đã ghép. Tài khoản cục bộ 2.2 cần đăng ký lại một lần; dữ liệu cũ không bị xóa.
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

Để cài đè bản đã phát hành, dùng cùng khóa ký; có thể đặt `MS51_DEBUG_KEYSTORE` tới keystore riêng ngoài repository. Khóa ký không được đưa vào Git.

## Kiểm thử

`CloudIntegrationTestRunner` là bài kiểm thử **live được gọi rõ ràng**: tạo tài khoản tạm trên VPS, ghép C5 thật, đọc/ghi cấu hình, kiểm tra truyền tệp và program/verify trên MS51 mô phỏng. Chỉ chạy trên emulator/bo thử nghiệm; nó thay tệp firmware đang lưu trên C5 bằng fixture kiểm thử và bỏ ghép khi xong.

    python tools/cloud_hardware_gateway.py
    adb -s emulator-5556 shell am instrument -w -e live true vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner

Lệnh Python chạy từ gốc repository, cần pyserial và C5 ở COM49 (sửa cổng khi cần). Adapter localhost:80 chỉ chuyển bước xác nhận LAN/ghép nối qua USB vì emulator không tham gia được AP vật lý; điều khiển từ xa đi qua HTTPS công khai và MQTT thật. Không xem bài này là kiểm thử radio provisioning trên điện thoại thật. Runner không chạy thao tác live nếu thiếu `-e live true`. Các fixture/runner AccountsDevices cũ là kiểm thử lịch sử của 2.2.

Vẫn cần thử quyền/chọn Wi-Fi của Android trên điện thoại thật và nhiều bo vật lý. NTC 10k chưa được hỗ trợ; dữ liệu MS51 trên C5 là mô phỏng.

Kiểm thử chuyển mạng 3.0.2 chỉ chạy khi chỉ định emulator: bật/tắt Wi-Fi và dữ liệu di động **trong emulator**, rồi phục hồi trạng thái ban đầu. Nó gọi `/health` HTTPS công khai, không tạo tài khoản, ghép lại hoặc gửi lệnh tới C5. Bài kiểm tra cố ý giữ binding Wi-Fi đã mất để chứng minh đường HTTP mặc định cũ thất bại trong khi phiên mới dùng được mạng hiện tại; đây là tình huống lỗi dựng lại, chưa phải xác nhận nguyên nhân chính xác trên điện thoại người dùng. Hai mạng emulator vẫn đi ra Internet qua máy chủ chạy emulator, không thay thế kiểm thử nhà mạng/SIM vật lý. Thử mất phản hồi của lệnh bằng fixture để xác nhận không tự gửi lại.

    adb -s emulator-5556 shell am instrument -w -e mode network -e emulator true vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner

Kiểm thử tìm thiết bị ở 3.0.1 chỉ đọc C5 thật, không đăng ký tài khoản hay ghi cấu hình. Thay IP/MAC bằng bo thử nghiệm cùng LAN với máy chạy emulator:

    adb -s emulator-5556 shell am instrument -w -e mode discovery -e device_ip 192.168.1.51 -e device_id ms51-001122334455 vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner

Runner kiểm tra IP mất/cũ, sai MAC/SSID, hủy thao tác, phạm vi dò mạng, phản hồi đến chậm và NSD bằng một HTTP fixture trên emulator. Fixture cần cổng 80: trên emulator thử nghiệm có `adb root`, tạm đặt `adb shell sysctl -w net.ipv4.ip_unprivileged_port_start=0`, rồi trả về giá trị ban đầu khi xong; không đổi cấu hình này trên điện thoại người dùng. Phần dò LAN dùng dải mạng thử nghiệm truyền vào thuật toán vì subnet của emulator khác LAN vật lý; đây không thay thế kiểm thử chọn Wi-Fi trên điện thoại thật.
