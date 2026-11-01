# Hẹn Giờ Lạnh 3.7.15

- Trong app, nhóm Khởi động nằm ngay dưới Chu trình trong Cài đặt nâng cao.
- Nút Xả đá bị khóa khi HDF đã lưu trên mạch bằng 1. Không dựa vào quyền hiện/ẩn HDF hoặc giá trị đang sửa chưa lưu.
- Áp dụng cho Internet và BLE. Tổng quan đọc HDF khi mở và khoảng 10 giây/lần; chưa đọc được hoặc dữ liệu quá 15 giây thì không cho nhấn Xả đá. Lỗi đọc thử lại sau 3 giây.
- Trước khi gửi lệnh Xả đá, app đọc lại cấu hình. HDF bật hoặc đọc thất bại thì không gửi lệnh. Không thay đổi điều kiện của nút Làm lạnh.
- Khi thoát Tổng quan hoặc đổi thiết bị, bỏ trạng thái HDF cũ và đọc lại. Không cần nạp lại C5/MS51.

Validation: assembleDebug, lintDebug, assembleDebugAndroidTest; managed UI checks cover HDF on/off/unknown/stale for both transports; BLE regression checks passed on Android emulator. Settings screenshot visually checked. No physical relay was actuated during validation.
