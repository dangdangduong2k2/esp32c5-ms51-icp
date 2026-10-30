# Hẹn Giờ Lạnh 3.6.2

Bố trí lại cài đặt theo video và cập nhật giao diện bám concept xanh lạnh. [Thay đổi, ảnh chụp và kiểm thử](../../docs/hen-gio-lanh-3.6.2/README.md). Chỉ cài đè APK; giữ VPS/C5/MS51 hiện có.

# Hẹn Giờ Lạnh 3.6.1

Production endpoint moved to the new VinaHost VPS; login and pair again with the new registry.
See [deployment and flashing notes](../../docs/vinahost-3.6.1/README.md). UI and MCU behavior are unchanged.
Legacy or other-server encrypted phone sessions are discarded before an authenticated request.

# MS51 Connect 3.3.7

Android native Java, màu navy/xanh ngọc, `vn.duong.ms51connect`, versionCode 35, Android 7.0 trở lên. Tài khoản và danh sách thiết bị nằm trên VPS; app giữ phiên đăng nhập được mã hóa bằng Android Keystore. Mật khẩu tài khoản dùng Argon2id ở server. Không có khôi phục qua email trong bản này.

3.3.7 gộp 10k/20k vào một ô **Nhiệt độ động cơ**, chỉ hiển thị cảm biến đang chọn. Nút hai mũi tên chuyển 10k ↔ 20k; nhãn cho biết loại đang xem. Lựa chọn được nhớ trên điện thoại theo tài khoản và thiết bị. **Nhiệt độ giám sát** là ô 50k riêng. Đổi ô xem không ghi xuống mạch, không đổi Điều kiện kích, không tự thay cảm biến khi đầu vào lỗi. Dữ liệu cũ/mất kết nối vẫn hiện chờ dữ liệu. Chỉ cập nhật APK.

3.3.6 thêm ảnh thiết bị kèm toàn bộ hộp trong suốt bên phải trạng thái trong thẻ Tổng quan. Ảnh tách nền từ hình người dùng cung cấp, hiển thị nguyên hình theo tỷ lệ và được đóng gói trong APK. Chữ trạng thái tự xuống dòng ở bên trái. Các số trên ảnh sản phẩm là minh họa cố định; hai ô thời gian phía dưới vẫn lấy dữ liệu từ mạch. Chỉ cập nhật APK.

3.3.5 bỏ chữ **/ connect** cạnh MS51 ở đầu trang và dòng **Theo dõi bộ điều khiển** dưới Tổng quan. Nút Đăng xuất vẫn nằm bên phải ở sảnh. Chỉ cập nhật APK.

3.3.4 chuyển tên thiết bị vào sau **Bộ điều khiển** trong thẻ Tổng quan, chữ 18sp medium và dòng trạng thái 29sp bold; bỏ dòng **Chế độ vận hành hiện tại**. Biểu tượng Wi-Fi đứng trước SSID, lấy RSSI của C5 từ `wifi.uplink.rssi` khi `state=connected`: từ −55 dBm là 4 mức, từ −67 là 3, từ −75 là 2, thấp hơn là 1. Thiếu RSSI, RSSI không hợp lệ hoặc mất kết nối hiện biểu tượng gạch chéo. Đây là số đo do firmware báo, không phải tín hiệu Wi-Fi của điện thoại. Chỉ cập nhật APK.

3.3.3 đưa ô LED đỏ sang trái, đổi nhãn CL thành **Thời gian chờ**; ô LED xanh ở bên phải, đổi nhãn OP thành **Thời gian hoạt động**. Giá trị và trạng thái nhấp nháy vẫn gắn với đúng màu như trước. Chỉ cập nhật APK.

3.3.2 đổi **Xả đá OP tính giây** và **Hiện ST LED** thành ô chọn **Tắt/Bật**, cùng kiểu với các mục chế độ phía trên. Giữ nguyên giá trị 0/1, cách đọc/lưu và quyền hiện/ẩn của admin. Chỉ cập nhật APK, không cần nạp lại bo.

3.3.1 nhận cách đấu mới **10k/IO5, 20k/IO6, 50k/IO4**, đồng thời giữ tương thích dữ liệu từ bo dùng cách đấu cũ. Cần nạp C5 mới để đổi chân đọc. [Bản nạp và kiểm thử](../../docs/ms51-connect-3.3.1/README.md).

3.3.0 có ba ô 10k/20k/50k và thẻ **Điều kiện kích**: chọn cảm biến hoặc SIG, nhập ngưỡng, Lưu. Cần cập nhật C5/MS51/server. [Đấu dây, giao thức, preview và kiểm thử](../../docs/ms51-connect-3.3.0/README.md).

3.2.4 giữ dòng **Theo dõi bộ điều khiển**, bỏ các khẩu hiệu ở Đăng nhập/Tạo tài khoản, sảnh và Cài đặt. Chỉ cập nhật APK.

3.2.3 bỏ icon khiên, các dòng Không gian thiết bị/Thiết bị của bạn và mục giấy phép khỏi giao diện; sảnh có nút chữ Đăng xuất. [Chi tiết](../../docs/ms51-connect-3.2.3/README.md).

3.2.2 đổi icon Xả đá thành viên đá lạnh, nền khi chọn cùng màu xanh với Làm lạnh. Chỉ cập nhật APK. [Preview](../../docs/ms51-connect-3.2.2/README.md).

3.2.1 bỏ Làm mới ở Tổng quan; hai nút chế độ có icon và phản hồi màu ngay khi chạm, kèm trạng thái gửi/xác nhận và khôi phục khi lỗi. Chỉ cập nhật APK. [Preview và kiểm thử](../../docs/ms51-connect-3.2.1/README.md).

3.2.0 thêm hai nút Làm lạnh/Xả đá cạnh Làm mới và lưu nguồn điều khiển cuối trên MS51. Cần cập nhật app, C5, MS51 và server. [Hướng dẫn, preview và kiểm thử](../../docs/ms51-connect-3.2.0/README.md).

3.1.6 chỉ vẽ các nét LED đang sáng, bỏ toàn bộ thanh mờ. Chỉ cần cài APK mới.

3.1.5 bỏ nhãn hai ô đếm và số 0 ở đầu, nhấp nháy theo số giảm; tách hai ô NTC 10k/50k độc lập. [Hướng dẫn và giới hạn nhận biết đếm](../../docs/ms51-connect-3.1.5/README.md).

3.1.4 hỗ trợ NTC ở IO6 với bản C5 trở lại DAT1/CLK0/RS3. Cần cập nhật APK và C5; giữ tương thích IO1 của 3.1.3. [Hướng dẫn](../../docs/ms51-connect-3.1.4/README.md).

3.1.3 thêm thẻ **Nhiệt độ** cho NTC 10 kΩ B3950 ở IO1, điện trở kéo lên 3,3 V là 10 kΩ. Cần nạp C5 DAT2/CLK3/RS4 mới, không cần đổi máy chủ. [Đấu dây và preview](../../docs/ms51-connect-3.1.3/README.md).

3.1.2 giản lược Tổng quan: chỉ giữ Làm mới, bỏ ba lối tắt và Dữ liệu trực tiếp. Rơ-le 1–3 có icon nguồn đổi màu theo trạng thái, luôn đi cùng chữ BẬT/TẮT/Chờ dữ liệu để không phụ thuộc màu sắc. Các nhãn UART/Firmware, thông tin chip và mạng được thay bằng ngôn ngữ thông thường. Bốn tab điều hướng vẫn có đủ Tổng quan, Cài đặt, Cập nhật và Thiết bị. Không thay đổi API hoặc dữ liệu thiết bị.

3.1.1 đổi Nhật ký thiết bị từ log trạng thái UART sang 30 thời lượng đã lưu trong MS51 (L00–L29). Giá trị 100 ms được đổi sang phút/giây; không tạo ngày giờ giả. Vị trí bằng 0 được ẩn; mảng vẫn giữ đúng số L sau khi chỉ số ghi quay vòng. Đọc mỗi 15 giây trong Tổng quan, hoặc bấm Làm mới; phản hồi trễ bị loại khi đổi màn hình/thiết bị. Mất kết nối giữ bản đọc trước có nhãn rõ ràng. Endpoint cloud riêng chỉ trả về lịch sử, dùng được khi cài đặt bị ẩn, không trả byte cấu hình/PIN. Không cần nạp firmware với bản UART hiện có. Xem [giao thức và kiểm thử](../../docs/ms51-connect-3.1.1/history.md).

3.1.0 thêm quyền theo từng thiết bị do admin quản lý. Mỗi mục trong 51 mục cài đặt, kể cả Min/Max, được bật/ẩn riêng. App lấy quyền khi mở Cài đặt và kiểm tra lại mỗi 15 giây; thay đổi quyền sẽ tải lại màn hình. Khi lưu, app chỉ gửi các giá trị đã sửa; server kiểm tra quyền và ghép vào cấu hình mới đọc từ MS51, giữ nguyên mục bị ẩn cùng byte dự phòng. Toàn bộ quyền tắt thì app không hiện biểu mẫu cài đặt.

Firmware chỉ có nút **Cập nhật** khi admin đã cấp HEX mới cho thiết bị, hoặc thông báo không có phiên bản mới. Không còn trình chọn tệp hay nút nạp/xóa nâng cao trong màn hình firmware của người dùng. Người dùng xác nhận cập nhật rồi server tiếp tục công việc, kể cả khi đóng app. App theo dõi trạng thái; server kiểm tra CRC ảnh C5, nạp theo generation, theo dõi đúng job_id rồi đối chiếu trước khi báo thành công. Kết quả chưa rõ không tự nạp lại; admin kiểm tra thiết bị rồi cấp lại file nếu cần.

Cần cập nhật máy chủ cùng bản 3.1.0. App cũ vẫn đăng nhập/xem thiết bị nhưng các lệnh firmware tự chọn và ghi cấu hình thô qua cloud bị từ chối. Cài đè APK cùng chữ ký giữ phiên tài khoản. Quyền áp dụng cho app/API cloud; giao diện local của C5 vẫn có cơ chế tin cậy LAN như trước.

Kiểm thử Android không kết nối bo: `adb shell am instrument -w -e mode managed vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner`. Kiểm tra ẩn từng trường, Min/Max riêng, PIN, các trạng thái firmware và chuyển thiết bị. Không chạy chế độ `live` cũ để xác minh luồng 3.1 trên bo đang dùng.

3.0.3 sửa nền thẻ trạng thái “Đang làm lạnh / Đang xả đá” bị xám khi vẽ lại. Khôi phục alpha trước khi vẽ gradient; kiểm thử Android xác nhận màu giữ nguyên qua 60 lần vẽ ở hai kích thước. APK cùng chữ ký với 3.0.2, cài đè giữ phiên đăng nhập và danh sách thiết bị.

3.0.2 mở HTTPS qua `ConnectivityManager.getActiveNetwork()` và `Network.openConnection()` ở **từng yêu cầu**, kể cả khi dùng lại phiên tài khoản sau đổi mạng. DNS/socket đi theo mạng hiện tại, độc lập với kết nối AP thiết lập. `registerDefaultNetworkCallback` làm mới các lần đọc ở trang chủ/tổng quan khi mạng thay đổi; phản hồi cũ không ghi đè trạng thái mới. Không tự gửi lại lệnh ghi khi mất phản hồi, không hạ xác thực TLS. Lỗi mất Internet trên điện thoại được hiển thị riêng với trạng thái thiết bị. [Android Network.openConnection](https://developer.android.com/reference/android/net/Network#openConnection(java.net.URL)) · [Theo dõi mạng mặc định](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).

3.0.1 sửa lỗi chưa tìm thấy C5 sau khi điện thoại đã vào Wi-Fi nhà. App dùng IP đã biết và Android NSD/mDNS theo MAC; nếu mất IP do C5 đổi băng tần, app dò HTTP trong tối đa một /24 thuộc Wi-Fi hiện tại (8 kết nối đồng thời, giới hạn 35 giây). Chỉ đọc `/api/status`; xác nhận cả MAC lẫn SSID trước khi gửi cấu hình ghép. Không dò mạng công cộng. Dừng tìm khi hủy/đổi bước. Mạng khách chặn thiết bị nội bộ vẫn cần chuyển sang mạng cho phép các thiết bị liên lạc.

Wi-Fi nhà dùng kết nối Internet thông thường của Android/cài đặt Wi-Fi hệ thống, thay vì `WifiNetworkSpecifier` dành cho kết nối nội bộ. HTTP tới bo được gắn với `Network` đã chọn; app không gắn toàn bộ tiến trình vào mạng AP. [Android Wi-Fi Network Request](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap) · [Android NSD](https://developer.android.com/reference/android/net/nsd/NsdManager).

## Sử dụng

1. Cài APK 3.1.6, đăng ký tài khoản hoặc đăng nhập. Cài đè bản 3.x giữ tài khoản và thiết bị đã ghép. Tài khoản cục bộ 2.2 cần đăng ký lại một lần; dữ liệu cũ không bị xóa.
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

Vẫn cần thử quyền/chọn Wi-Fi của Android trên điện thoại thật và nhiều bo vật lý. Bản mô phỏng lịch sử chưa có NTC; bản native DAT2/CLK3/RS4 từ 3.1.3 hỗ trợ NTC ở IO1. Chưa kiểm tra cảm biến thực tế trên biến thể này.

Kiểm thử chuyển mạng 3.0.2 chỉ chạy khi chỉ định emulator: bật/tắt Wi-Fi và dữ liệu di động **trong emulator**, rồi phục hồi trạng thái ban đầu. Nó gọi `/health` HTTPS công khai, không tạo tài khoản, ghép lại hoặc gửi lệnh tới C5. Bài kiểm tra cố ý giữ binding Wi-Fi đã mất để chứng minh đường HTTP mặc định cũ thất bại trong khi phiên mới dùng được mạng hiện tại; đây là tình huống lỗi dựng lại, chưa phải xác nhận nguyên nhân chính xác trên điện thoại người dùng. Hai mạng emulator vẫn đi ra Internet qua máy chủ chạy emulator, không thay thế kiểm thử nhà mạng/SIM vật lý. Thử mất phản hồi của lệnh bằng fixture để xác nhận không tự gửi lại.

    adb -s emulator-5556 shell am instrument -w -e mode network -e emulator true vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner

Kiểm thử tìm thiết bị ở 3.0.1 chỉ đọc C5 thật, không đăng ký tài khoản hay ghi cấu hình. Thay IP/MAC bằng bo thử nghiệm cùng LAN với máy chạy emulator:

    adb -s emulator-5556 shell am instrument -w -e mode discovery -e device_ip 192.168.1.51 -e device_id ms51-001122334455 vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner

Runner kiểm tra IP mất/cũ, sai MAC/SSID, hủy thao tác, phạm vi dò mạng, phản hồi đến chậm và NSD bằng một HTTP fixture trên emulator. Fixture cần cổng 80: trên emulator thử nghiệm có `adb root`, tạm đặt `adb shell sysctl -w net.ipv4.ip_unprivileged_port_start=0`, rồi trả về giá trị ban đầu khi xong; không đổi cấu hình này trên điện thoại người dùng. Phần dò LAN dùng dải mạng thử nghiệm truyền vào thuật toán vì subnet của emulator khác LAN vật lý; đây không thay thế kiểm thử chọn Wi-Fi trên điện thoại thật.

Kiểm thử nền thẻ trạng thái (offline, không gọi server hoặc điều khiển C5):

    adb -s emulator-5556 shell am instrument -w -e mode render vn.duong.ms51connect.test/vn.duong.ms51control.CloudIntegrationTestRunner


## 3.1.7 — Nhấp nháy theo trạng thái bộ đếm

Chỉ ô số trong app nhấp nháy. Giá trị vẫn lấy từ `green_min`/`red_min`, được MS51 giải mã từ ảnh số đang ghi cho LED vật lý. Không thay đổi cách hiển thị hoặc nhấp nháy LED dưới mạch.

MS51 bổ sung `VAR,countdown_active,GREEN|RED|NONE` mỗi giây. App nhấp nháy ngay từ mẫu đầu, không so sánh số giảm, không hết hạn sau 70 giây nếu dữ liệu trạng thái vẫn mới. `NONE`, cài đặt, dữ liệu không hợp lệ/cũ và tạm dừng từ xa (nếu phiên bản MS51 cung cấp) dừng hiệu ứng. Bản MS51 cũ không có trường mới vẫn hiển thị số nhưng không suy đoán bên đang đếm.

Cần APK 3.1.7 và bản MS51 có trường mới. C5 hiện tại chuyển tiếp trường này mà không cần nạp lại; máy chủ không đổi. HEX kèm phát hành được build từ `MS51_code/260502 add PIN`, cùng source thư mục người dùng cung cấp; chưa nạp kiểm thử trên MS51 thật. Bo quan sát trước cập nhật có các trường `physical_mode`, `effective_mode`, `remote_paused` không có trong source này, vì vậy cần xác nhận bản source trước khi nạp thay thế.

Keil chuyển từ `OPTIMIZE(8,SPEED)` sang `OPTIMIZE(8,SIZE)` để giữ nguyên giới hạn `CODE(0x0000-0x6EFF)`: 26.659 byte code, 0 lỗi/cảnh báo. Không mở rộng vùng flash sang vùng dành riêng. Xem `docs/ms51-connect-3.1.7/test-results.json`.


## 3.1.8 — Xác minh trên MS51 thật

Đã nạp HEX MS51 3.1.7 từ source người dùng xác nhận ngày 13/10/2026. C5 báo nạp thành công với kiểm tra từng trang flash; cấu hình 217 byte trước/sau giống nhau. Đã quan sát trạng thái GREEN sau khởi động và RED ở pha kế tiếp; ba mẫu liên tục cùng số đỏ 4 vẫn báo RED.

App bỏ qua trường `remote_paused` tùy chọn đã cũ còn trong bộ nhớ C5 sau khi thay MS51. Trạng thái bộ đếm hiện tại vẫn quyết định hiệu ứng; trạng thái tạm dừng mới (nếu có) vẫn được tôn trọng. Không cần khởi động lại/nạp lại C5 để xóa dữ liệu cũ. Kiểm thử emulator API 35 đạt, cùng chữ ký APK trước. Bản này dùng nguyên HEX MS51 của 3.1.7; không có thay đổi chương trình MS51 thêm.


## 3.1.9 — Nhãn OP và CL

Ô xanh có nhãn OP, ô đỏ có nhãn CL phía trên số. Giữ hiệu ứng nhấp nháy theo trạng thái bộ đếm của 3.1.8. Chỉ cập nhật APK, không cần nạp C5/MS51. Đã kiểm thử emulator API 35, lint không có lỗi và kiểm tra ảnh giao diện trong `docs/ms51-connect-3.1.9`.


## 3.1.10 — Bỏ đơn vị dưới ô số

Bỏ chữ “phút” dưới cả OP và CL. Nhãn OP/CL, số hiển thị và hiệu ứng nhấp nháy giữ nguyên. Chỉ cập nhật APK. Build thành công, lint không có lỗi, chữ ký giữ nguyên.
