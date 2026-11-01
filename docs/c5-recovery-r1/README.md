# C5 Recovery R1 — cập nhật MS51 có bản cứu hộ

## Phạm vi

Bo chuẩn: DAT IO1, CLK IO0, RS IO3; NTC 10k IO5, 20k IO6, 50k IO4.
Bản xuất `ESP32-C5-STANDARD-RECOVERY-R1.zip` dành cho C5 **16 MB** đang kiểm tra,
không phải gói test IO1/N4. Giữ BLE, Wi-Fi, VPS hiện tại và bản sửa bộ đệm HEX 4 KiB.
Không đổi app Android hoặc logic vận hành của chương trình MS51.

## Cơ chế

1. Tải HEX đầy đủ vào vùng upload A/B, xác thực file và đối chiếu CRC với bản admin cấp.
2. Vào ICP, kiểm tra đúng dòng chip/cấu hình, đọc hai lần từng trang APROM.
3. Lưu bản APROM cũ và bản dự kiến mới vào hai vùng riêng trên C5. Bản mới ghép
   dữ liệu HEX với dữ liệu cũ tại những byte không có trong HEX, kể cả cấu hình cuối
   APROM. Đọc lại flash C5 và kiểm tra CRC của cả hai bản trước khi cho phép xóa MS51.
4. Ghi journal có checksum và marker ARMED cuối cùng. Nếu chưa ghi/đọc lại marker
   thành công, không xóa trang nào trên MS51.
5. Chỉ ghi các trang khác nhau, đọc lại từng trang, tối đa ba lần ghi nếu không khớp.
   Luôn đối chiếu **toàn bộ APROM** trước khi ghi marker hoàn thành và nhả reset.
6. Nếu ghi/đọc đối chiếu lỗi: lưu hướng rollback, thử phục hồi bản cũ. Nếu mất dây
   hoặc chưa có nguồn MS51, giữ reset thấp giữa các lần thử và tự thử lại, giãn từ
   5 đến tối đa 30 giây. Không cần Internet để thực hiện bước này.
7. C5 khởi động lại sẽ đọc journal trước khi khởi động UART/Wi-Fi. Tác vụ dở dang
   được tiếp tục từ hai ảnh đã lưu. Trang đã đúng được bỏ qua. Không cần tải lại HEX.

Marker rollback được lưu trước khi ghi lại bản cũ; marker ghi dở được xử lý theo
hướng bảo thủ. Nếu ảnh mới hỏng thì chuyển sang bản cũ. Nếu ảnh cần phục hồi hoặc
journal không hợp lệ, không tự báo hoàn thành hoặc tự nhả reset.

Trong lúc chờ phục hồi, chặn lệnh nạp khác, reset, mass erase, đọc ICP thủ công và
verify thủ công. Vùng cứu hộ độc lập với vùng upload: upload file mới không ghi đè
bản cứu hộ. Các lệnh thao tác qua UART không hoạt động khi UART đã tạm dừng cho ICP.

## Internet và máy chủ

- Tải file có thể thử lại khi mạng gián đoạn, tối đa trong cửa sổ 10 phút; chưa ghi MS51
  trước khi tải và kiểm tra xong.
- Khi đã gửi lệnh nạp nhưng mất phản hồi, **không gửi lặp lệnh nạp**. Đọc trạng thái
  hoặc bằng chứng đối chiếu đã lưu trên C5: CRC file, số giao dịch mới hơn và kết quả.
- Đợi kết nối trở lại tối đa 10 phút mỗi giai đoạn. Nếu vẫn chưa rõ, giữ trạng thái
  chưa xác định, không đoán là thành công.
- Sau khi server khởi động lại hoặc job đã chuyển sang chưa xác định, lần app đọc
  trạng thái sẽ đối chiếu heartbeat mới với baseline/CRC đã lưu trong SQLite.
- Phục hồi bản cũ được báo là cập nhật thất bại đã phục hồi, không đánh dấu bản mới
  là đã cài. Không dùng heartbeat cũ/offline hoặc kết quả giao dịch cũ làm bằng chứng.

## Bảng phân vùng và nạp

Các vùng cũ không đổi vị trí/kích thước. Thêm `ms51safe` tại `0x224000`, dài `0x11000`:

| Vị trí tương đối | Nội dung |
|---|---|
| 0x0000–0x0FFF | Journal, CRC, marker và cấu hình chip |
| 0x1000–0x8FFF | 32 KiB dự phòng cho APROM cũ |
| 0x9000–0x10FFF | 32 KiB dự phòng cho APROM mới đã ghép |

Nạp ba file:

| Địa chỉ | File |
|---|---|
| 0x2000 | bootloader.bin |
| 0x8000 | partition-table.bin |
| 0x10000 | ESP32-C5-STANDARD-RECOVERY-R1-app.bin |

**Lần nâng cấp đầu phải nạp bảng phân vùng mới**, không chỉ app. Không Erase All.
Không hạ về firmware C5 cũ hoặc xóa vùng cứu hộ khi tác vụ đang chờ phục hồi.
Build ESP-IDF 6.0, project version `C5-RECOVERY-R1`; gói kèm sdkconfig và patch USB
reset của bản chuẩn. Feature chạy trên C5, không yêu cầu đổi code MS51 để dùng ICP.

## Kiểm chứng đã thực hiện

- Host test sử dụng chính parser, journal và thuật toán nạp của firmware, với mô hình
  flash NOR và bộ nhớ MS51. Cắt nguồn giả lập giữa từng bước xóa/ghi snapshot, ARMED,
  trang MS51, rollback, DONE; khởi động lại và kiểm tra byte-for-byte.
- Giả lập đứt dây tại từng bước ghi, nối lại, mất nguồn giữa lúc rollback, snapshot
  hỏng, journal hỏng, ghi snapshot thất bại, cấu hình chip không khớp và HEX thưa.
- Chỉ cho kết thúc với toàn bộ bản cũ hoặc bản mới; trường hợp không có ảnh hợp lệ
  phải giữ trạng thái pending. Giữ nguyên byte ngoài phạm vi HEX và cấu hình cuối APROM.
- 43 test server/admin: tương thích bo cũ; mất ACK/mất mạng không gửi lại program;
  không nhận kết quả cũ/sai CRC; không dùng heartbeat hết hạn; rollback không đánh dấu
  đã cài; đợi worker dọn dẹp dù đã có bằng chứng verify.
- Bo thật: nạp C5/bảng phân vùng, đối chiếu NVS trước/sau giống nhau. Chạy cập nhật
  có bảo vệ với `MS51-Bluetooth-3.7.4.hex`, hoàn tất khoảng 82 giây. CRC nguồn
  820705851, journal `verified`, cấu hình MS51 trước/sau giống nhau và UART trở lại.
- **Chưa cắt nguồn/rút dây vật lý giữa lúc ghi trên bo thật.** Các tình huống đó đã
  kiểm tra bằng fault injection; không mô tả chúng như đã thử phá lỗi trên phần cứng.

## Giới hạn bắt buộc phải hiểu

Đây không phải cam kết chống mọi lỗi phần cứng:

- C5 mất nguồn riêng trong khi MS51 còn nguồn, dây RS/GND đứt, GPIO hỏng hoặc giai
  đoạn C5 còn đang boot: phần mềm C5 không thể bảo đảm giữ reset liên tục.
- Trình tự vào ICP cần các xung reset; giữ reset được áp dụng giữa các lần thử,
  không thay thế một mạch khóa đầu ra tải độc lập.
- Không bảo đảm trạng thái relay an toàn chỉ nhờ reset MCU: còn phụ thuộc cực tính,
  điện trở và mạch driver của từng bo.
- Nối lại **cùng bo MS51**. Hiện kiểm tra dòng chip và CONFIG, chưa ràng buộc theo
  UID riêng từng chip; không chuyển dây sang bo khác trong khi đang phục hồi.
- Flash vật lý hỏng, cả hai ảnh cứu hộ hỏng hoặc journal không đọc được cần cứu hộ
  thủ công. Hai ảnh chỉ sao lưu APROM; không tự mass erase hay khôi phục CONFIG/LDROM.
- Checksum và đọc đối chiếu xác nhận dữ liệu nạp đúng, không chứng minh logic của một
  firmware mới là đúng. Chưa có thử chạy rồi tự rollback theo sức khỏe ứng dụng MS51.

Muốn khóa tải xuyên suốt cả khi C5 mất nguồn/boot hoặc đứt reset, cần thiết kế thêm
mạch giám sát nguồn và chân cho phép tải mặc định OFF, cùng cơ chế bootloader chặn
chạy firmware dở dang trên MS51. Phải dựa sơ đồ driver thực tế để chọn mức an toàn;
không thể hoàn thành phần bảo vệ điện này bằng việc đổi code C5.

## Chạy host test

```powershell
cc -std=c11 -Wall -Wextra -Werror -I tests/image -I main tests/image/recovery_test.c main/ms51_image.c -o D:/work/recovery_test.exe
D:/work/recovery_test.exe
$env:MS51_TEST_WORK='D:/work/server-tests'
python server/test_managed.py
```
