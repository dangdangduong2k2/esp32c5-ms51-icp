# MS51 Connect 2.2

Ứng dụng Android native Java, giao diện navy và xanh ngọc. Nguồn gốc: dangdangduong2k2/esp32c5-ms51-icp (MIT).

## Tài khoản và thiết bị

- Tạo tài khoản/đăng nhập trên điện thoại; không có máy chủ, đồng bộ hoặc khôi phục mật khẩu qua email. Giữ phiên đăng nhập đến khi Đăng xuất ở biểu tượng tài khoản góc phải.
- Tên đăng nhập 3–32 ký tự không dấu; mật khẩu 8–128 ký tự, lưu bằng PBKDF2 với salt riêng. Không lưu mật khẩu tài khoản dạng rõ. Mật khẩu Wi-Fi chỉ dùng trong quá trình ghép nối, không lưu vào danh sách thiết bị.
- Mỗi tài khoản có danh sách riêng, nhiều thiết bị được phép dùng cùng một Wi-Fi. Khóa nhận diện là MAC gốc của ESP, không phải IP hoặc SSID router. Ghép lại cùng MAC cập nhật bản ghi, không nhân đôi.
- Trang đầu hiển thị danh sách. Chạm thẻ mở điều khiển; giữ thẻ để đổi tên/bỏ ghép nối. Bỏ ghép chỉ xóa bản ghi thuộc tài khoản đang dùng.
- Dữ liệu chỉ nằm trên điện thoại, tắt sao lưu/di chuyển dữ liệu của Android. Xóa dữ liệu hoặc gỡ app sẽ mất tài khoản và danh sách. Tài khoản là lớp quản lý của app; chưa có xác thực tài khoản trên API ESP.
- Khi nâng cấp từ 2.1, thiết bị cũ được chuyển vào tài khoản đầu tiên và yêu cầu ghép lại để lấy MAC; không tin IP cũ có thể đã cấp cho bo khác.

## Luồng Thêm thiết bị

1. **Tìm Wi-Fi thiết bị**: Android cho chọn AP có dạng `MS51-PROGRAMMER-<12 ký tự MAC>`, ví dụ `MS51-PROGRAMMER-3844BE1FE4E0`. Mật khẩu AP vẫn `12345678`. App đọc được API và mã MAC trước khi đi tiếp.
2. **Quét Wi-Fi**: chỉ quét khi bấm nút; hiển thị 2.4 GHz/5 GHz. Chạm mạng muốn dùng.
3. Màn nhập mật khẩu riêng, có biểu tượng con mắt; sau đó **Kết nối**. Mạng mở không cần mật khẩu.
4. **Tiếp tục** để điện thoại vào cùng mạng. Chỉ lưu thiết bị sau khi xác nhận đúng MAC, SSID và IP trên mạng đích. Trở về danh sách khi hoàn tất.

Từng bước khóa thao tác trong lúc xử lý, bỏ qua phản hồi cũ khi đổi luồng. WifiNetworkSpecifier không ép băng tần và không yêu cầu Internet. Android cũ/từ chối quyền dùng cài đặt Wi-Fi từ chính nút hiện tại.

## Trạng thái Internet

Firmware cung cấp `/api/status` với `device.id`, `device.hostname` và `internet.state/checked_age_ms`. Mỗi 20 giây khi có IP STA, ESP thử HTTP GET tới `http://connectivitycheck.gstatic.com/generate_204`, timeout 6 giây, không theo redirect. Không gửi tên tài khoản, SSID, mật khẩu hoặc MAC trong yêu cầu. Đây là kiểm tra khả năng truy cập điểm kiểm tra, không phải chứng thực bảo mật đường truyền.

- **Có Internet**: ESP nhận HTTP 204 gần đây (không quá 90 giây).
- **Chưa có Internet**: ESP báo STA chưa kết nối/có IP.
- **Đang kiểm tra Internet / Chưa xác nhận Internet**: chưa có kết quả, kết quả quá cũ, hoặc điểm kiểm tra không trả 204. Chặn tên miền này có thể khiến trạng thái chưa xác nhận dù Internet vẫn hoạt động.
- **Không liên lạc được**: điện thoại chưa đọc được ESP. Không suy ra ESP mất Internet chỉ từ lỗi điện thoại.

App làm mới danh sách trong lúc mở trang, qua IP đã lưu rồi tên mDNS riêng `ms51-<mac>.local`. Cần cùng mạng nội bộ; chưa theo dõi từ xa qua 4G/máy chủ.

## Điều khiển và tránh nhầm bo

Giữ bốn tab Tổng quan/Cài đặt/Firmware/Thiết bị, codec 217 byte, upload/program/verify/reset và CONFIRM cho xóa/ghi toàn bộ. Mỗi yêu cầu chốt URL, Network và mã bo trước khi xếp hàng. Firmware từ chối `X-MS51-Device` không khớp bằng HTTP 409; app cũng đối chiếu ID trong status. Đổi thiết bị/đăng xuất bỏ dữ liệu và callback cũ. Không gọi đây là xác thực bằng mật khẩu hoặc thay thế toàn bộ cơ chế phân quyền thiết bị.

MS51 trên bản firmware thử nghiệm vẫn là mô phỏng trên C5. Chưa đọc NTC 10k, chưa điều khiển MS51 thật. Luồng này chưa bao gồm xử lý mọi xung đột điều khiển từ các client khác nhau.

## Build/kiểm thử

JDK 17, Android SDK 35, Gradle wrapper; minSdk 24; applicationId `vn.duong.ms51connect`; versionCode 7, versionName 2.2.0. APK ký debug, cùng khóa các bản thử nghiệm trước.

    gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug

Instrumentation dùng Activity/HTTP client thật trên emulator API 35, mô phỏng callback radio và hai ESP HTTP; chưa phải thử hai bo vật lý. Chạy fixture từ thư mục source Android:

    python tests/accounts_mock.py
    adb shell am instrument -w vn.duong.ms51connect.test/vn.duong.ms51control.AccountsDevicesTestRunner

Fixture nghe localhost:80; emulator truy cập 10.0.2.2. Bản source có dữ liệu config trong tests/config.hex. Báo cáo kết quả và ảnh thực tế từ emulator nằm ở gói accounts-2.2.

Firmware 2.2 đã build cho C5, chưa nạp trong đợt này do người dùng chưa tiện đưa bo vào chế độ nạp. APK cần firmware 2.2 để ghép theo MAC. Chưa xác nhận RF 5 GHz, nhiều bo vật lý, hoặc trạng thái HTTP 204 thực trên bo với phiên bản này.
