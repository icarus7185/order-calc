# Requirement Specification — Order Calc (thư viện tính hoá đơn từ CSV)

| Mục | Giá trị |
|---|---|
| Nguồn | [business-requirement.md](business-requirement.md) |
| Phiên bản | 1.1 |
| Ngày | 2026-09-24 |
| Trạng thái | Đã chốt — toàn bộ câu hỏi mở đã được xác nhận (mục 9) |

> Quy ước: mọi quyết định có mã `Qx` đã được business xác nhận, xem mục 9. Các điểm đánh dấu **[THIẾT KẾ]** là quyết định kỹ thuật của nhóm phát triển, không làm thay đổi hành vi nghiệp vụ.

### Lịch sử thay đổi
| Phiên bản | Thay đổi |
|---|---|
| 0.1 | Bản nháp đầu tiên |
| 0.2 | Xác nhận: đơn giá chưa gồm VAT; dấu phân cách `,`; header tiếng Anh; %VAT nhận mọi giá trị; làm tròn 1 chữ số thập phân chỉ trên tổng hoá đơn |
| 1.0 | Xác nhận: giới hạn 10.000 dòng dữ liệu (thêm mã lỗi `TOO_MANY_ROWS`); `total_after_tax = round(Σ amount_after_tax)`; chấp nhận toàn bộ giả định còn lại. Sửa lỗi cộng sai tổng VAT trong ví dụ mục 5.4 của bản 0.2 |
| 1.1 | Thêm mã lỗi `MALFORMED_CSV` (phát hiện khi implement) |

---

## 1. Mục tiêu và phạm vi

### 1.1 Mục tiêu
Cung cấp một thư viện Java, đóng gói thành file `*.jar`, nhận vào một file CSV biểu diễn **một hoá đơn**, kiểm tra hợp lệ, tính tiền thuế GTGT (VAT) cho từng dòng và tổng hoá đơn, rồi ghi ra một file CSV kết quả.

### 1.2 Trong phạm vi
- Đọc 1 file CSV input (1 file = 1 hoá đơn).
- Validate dữ liệu input; dừng và trả lỗi khi không hợp lệ.
- Tính cho từng dòng: số tiền trước thuế, tiền VAT, số tiền sau thuế.
- Tính tổng 3 giá trị trên cho toàn hoá đơn.
- Ghi file CSV output gồm các cột input + 3 cột mới + 1 dòng tổng ở cuối.
- Public API Java để ứng dụng khác gọi.

### 1.3 Ngoài phạm vi
- Giao diện người dùng, CLI, REST API (có thể bổ sung sau).
- Xử lý nhiều hoá đơn trong 1 file, hoặc gộp nhiều file.
- Tra cứu mã mặt hàng / đơn giá từ nguồn dữ liệu ngoài.
- Gộp các dòng trùng mã mặt hàng (Q6).
- Chiết khấu, phí vận chuyển, đa tiền tệ.
- Lưu trữ vào database.

---

## 2. Thuật ngữ

| Thuật ngữ | Tên cột (CSV) | Ý nghĩa |
|---|---|---|
| Mã mặt hàng | `item_code` | Mã định danh mặt hàng |
| Số lượng | `quantity` | Số lượng mua |
| Đơn giá | `unit_price` | Giá 1 đơn vị, **chưa gồm VAT** (Q3) |
| % VAT | `vat_rate` | Thuế suất tính theo phần trăm, ví dụ `10` = 10% |
| Số tiền trước thuế | `amount_before_tax` | Tiền hàng chưa VAT |
| Thành tiền VAT | `vat_amount` | Tiền thuế VAT |
| Số tiền sau thuế | `amount_after_tax` | Tiền hàng đã gồm VAT |

---

## 3. Đặc tả Input

### 3.1 Định dạng file
| Thuộc tính | Quy định |
|---|---|
| Định dạng | CSV theo RFC 4180 (hỗ trợ giá trị trong dấu `"`) |
| Encoding | UTF-8, chấp nhận có hoặc không có BOM |
| Dấu phân cách | Dấu phẩy `,`, cố định, không cấu hình (Q1) |
| Xuống dòng | `\n` hoặc `\r\n` |
| Header | Bắt buộc, là dòng đầu tiên |
| Dòng trống | Bỏ qua dòng hoàn toàn trống (kể cả dòng trống cuối file) |

### 3.2 Header
- Header phải chứa đúng 4 cột: `item_code,quantity,unit_price,vat_rate` (Q2).
- So khớp tên cột: không phân biệt hoa/thường, bỏ khoảng trắng hai đầu.
- Thứ tự cột: xác định theo tên trong header, không phụ thuộc vị trí. Output giữ nguyên thứ tự cột như input.
- Không được thiếu cột, trùng cột, hay có cột lạ.

### 3.3 Quy tắc dữ liệu từng cột

| Cột | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| `item_code` | Chuỗi | Có | Không rỗng sau khi trim; tối đa 50 ký tự [THIẾT KẾ]. Cho phép trùng mã giữa các dòng, không gộp (Q6) |
| `quantity` | Số thập phân | Có | `> 0`; tối đa 3 chữ số thập phân (Q4) |
| `unit_price` | Số thập phân | Có | `>= 0`; tối đa 2 chữ số thập phân [THIẾT KẾ] |
| `vat_rate` | Số thập phân | Có | Mọi số `>= 0` (Q5, Q16): không giới hạn trên, không giới hạn số chữ số thập phân; từ chối số âm |

Định dạng số:
- Dấu thập phân là dấu chấm `.`; **không** chấp nhận dấu phân cách hàng nghìn, ký hiệu tiền tệ, ký hiệu `%`.
- Cho phép khoảng trắng hai đầu (sẽ được trim).
- Không chấp nhận dạng khoa học (`1e5`), `NaN`, `Infinity`.

### 3.4 Ràng buộc mức file
- Phải có ít nhất 1 dòng dữ liệu sau header; file chỉ có header là lỗi (Q7).
- Tối đa **10.000 dòng dữ liệu** (không tính header và dòng trống) (Q15). Đúng 10.000 dòng là hợp lệ; từ dòng thứ 10.001 là lỗi `TOO_MANY_ROWS`.
- Mỗi dòng dữ liệu phải có đúng số cột bằng header.

---

## 4. Đặc tả xử lý

### 4.1 Luồng xử lý
```
1. Mở file input  ──(lỗi IO)──────────────► trả lỗi, dừng
2. Đọc & validate header ──(không hợp lệ)─► trả lỗi, dừng
3. Đọc & validate từng dòng dữ liệu
      ├─ gặp dòng dữ liệu thứ 10.001 ─────► thêm lỗi TOO_MANY_ROWS, ngừng đọc tiếp
      └─ gom TẤT CẢ lỗi của các dòng đã đọc
   Có lỗi? ─────────────────────────────────► trả danh sách lỗi, dừng, KHÔNG ghi output
4. Tính toán từng dòng — giá trị chính xác, không làm tròn (mục 4.2)
5. Tính tổng hoá đơn, rồi làm tròn 1 chữ số thập phân (mục 4.3)
6. Ghi file output (mục 5)
```

- **Chiến lược validate**: gom toàn bộ lỗi dữ liệu trong file rồi trả về một lần, không dừng ở lỗi đầu tiên (Q8). Lỗi header / IO thì dừng ngay vì không thể đọc tiếp.
- **Vượt giới hạn dòng**: ngừng đọc ngay khi gặp dòng dữ liệu thứ 10.001, không đọc hết file quá lớn. Lỗi `TOO_MANY_ROWS` được trả về cùng các lỗi đã gom từ 10.000 dòng trước đó.
- **Tính nguyên tử**: khi có lỗi, không tạo file output và không ghi đè file output đã tồn tại. Ghi ra file tạm rồi rename khi thành công [THIẾT KẾ].

### 4.2 Công thức tính từng dòng — không làm tròn
Toàn bộ tính toán dùng `java.math.BigDecimal` với phép tính **chính xác** (không dùng `double`/`float`, không dùng `MathContext` giới hạn độ chính xác).

```
amount_before_tax = quantity × unit_price
vat_amount        = amount_before_tax × vat_rate / 100
amount_after_tax  = amount_before_tax + vat_amount
```

- Giá trị từng dòng **không làm tròn** (Q10). Cả 3 phép tính đều cho kết quả hữu hạn (chia cho 100 luôn chính xác), nên không mất độ chính xác.
- Mọi dòng luôn thoả `amount_after_tax = amount_before_tax + vat_amount`.

### 4.3 Tổng hoá đơn — làm tròn 1 chữ số thập phân
Mỗi tổng được tính **độc lập** bằng cách cộng các giá trị chính xác, chưa làm tròn, của từng dòng, rồi mới làm tròn (Q9, Q10, Q17):

```
total_before_tax = round( Σ amount_before_tax )
total_vat        = round( Σ vat_amount )
total_after_tax  = round( Σ amount_after_tax )
```

- `round(x)`: làm tròn về **1 chữ số thập phân**, chế độ `HALF_UP` (Q9b).
- Vì 3 tổng được làm tròn độc lập, dòng TOTAL **có thể lệch 0.1** so với đẳng thức `total_after_tax = total_before_tax + total_vat` (xem AC-13). Đây là hành vi đúng theo Q17, không phải lỗi.

---

## 5. Đặc tả Output

### 5.1 Định dạng file
- CSV RFC 4180, UTF-8 **không BOM** (Q11), dấu phân cách `,`, xuống dòng `\n`.
- Chỉ đặt giá trị trong dấu `"` khi cần (chứa `,`, `"` hoặc xuống dòng).
- Đường dẫn file output do bên gọi truyền vào (Q14).

### 5.2 Cột
Các cột input (giữ nguyên thứ tự cột và giá trị gốc đã trim), sau đó thêm 3 cột theo thứ tự như business requirement:

| # | Cột | Nguồn |
|---|---|---|
| 1–4 | `item_code`, `quantity`, `unit_price`, `vat_rate` | Từ input |
| 5 | `vat_amount` | Tính toán |
| 6 | `amount_before_tax` | Tính toán |
| 7 | `amount_after_tax` | Tính toán |

Cách ghi số:
- **Dòng dữ liệu**: giá trị chính xác, dạng thuần (`stripTrailingZeros().toPlainString()`), bỏ số 0 thừa sau dấu thập phân, không phân cách hàng nghìn. Ví dụ `20000`, `3999.9996`, `0.04`.
- **Dòng tổng**: luôn có đúng 1 chữ số thập phân. Ví dụ `296500.0`, `27720.0`.
- Thứ tự dòng dữ liệu giữ nguyên như input.

### 5.3 Dòng tổng
- Là dòng cuối cùng của file.
- `item_code` = `TOTAL`; `quantity`, `unit_price`, `vat_rate` để trống; không tính tổng số lượng (Q12).
- 3 cột mới chứa `total_vat`, `total_before_tax`, `total_after_tax`.

### 5.4 Ví dụ

Input:
```csv
item_code,quantity,unit_price,vat_rate
SP001,2,100000,10
SP002,3,15500,8
SP003,1.5,33333.33,8
```

Tính toán từng dòng:

| Dòng | before | VAT | after |
|---|---|---|---|
| SP001 | 2 × 100000 = 200000 | 200000 × 10% = 20000 | 220000 |
| SP002 | 3 × 15500 = 46500 | 46500 × 8% = 3720 | 50220 |
| SP003 | 1.5 × 33333.33 = 49999.995 | 49999.995 × 8% = 3999.9996 | 53999.9946 |
| **Σ chính xác** | 296499.995 | 27719.9996 | 324219.9946 |
| **TOTAL (làm tròn)** | `296500.0` | `27720.0` | `324220.0` |

Output:
```csv
item_code,quantity,unit_price,vat_rate,vat_amount,amount_before_tax,amount_after_tax
SP001,2,100000,10,20000,200000,220000
SP002,3,15500,8,3720,46500,50220
SP003,1.5,33333.33,8,3999.9996,49999.995,53999.9946
TOTAL,,,,27720.0,296500.0,324220.0
```

---

## 6. Public API

Package gốc: `com.ordercalc` (Q13).

```java
public final class InvoiceCalculator {
    /** Số dòng dữ liệu tối đa của 1 file input. */
    public static final int MAX_DATA_ROWS = 10_000;

    public InvoiceCalculator();                       // cấu hình mặc định
    public InvoiceCalculator(CalculatorConfig config);

    /** Đọc input, validate, tính toán và ghi output. */
    public InvoiceResult process(Path input, Path output)
            throws InvoiceValidationException, IOException;

    /** Biến thể stream để tích hợp không qua file hệ thống. */
    public InvoiceResult process(InputStream input, OutputStream output)
            throws InvoiceValidationException, IOException;
}

/** Các giá trị tổng đã làm tròn, giống dòng TOTAL trong file output. */
public record InvoiceResult(
        int lineCount,
        BigDecimal totalBeforeTax,
        BigDecimal totalVat,
        BigDecimal totalAfterTax) {}

public record CalculatorConfig(
        int totalScale,               // mặc định 1
        RoundingMode roundingMode) {} // mặc định HALF_UP

public class InvoiceValidationException extends Exception {
    public List<ValidationError> getErrors();
}

public record ValidationError(
        int lineNumber,     // số dòng vật lý trong file, header = 1
        String column,      // null nếu là lỗi mức file/header/dòng
        ErrorCode code,
        String message) {}
```

- Lỗi dữ liệu → `InvoiceValidationException` (chứa danh sách lỗi).
- Lỗi đọc/ghi file → `IOException`.
- Tham số `null` → `NullPointerException` (fail fast).
- `InvoiceCalculator` không giữ trạng thái giữa các lần gọi → thread-safe.
- Dấu phân cách và giới hạn 10.000 dòng là quy định nghiệp vụ cố định, không nằm trong `CalculatorConfig`.

### 6.1 Danh mục mã lỗi

| Mã | Mức | Điều kiện |
|---|---|---|
| `EMPTY_FILE` | File | File rỗng, không có header |
| `NO_DATA_ROWS` | File | Chỉ có header, không có dòng dữ liệu |
| `TOO_MANY_ROWS` | File | Số dòng dữ liệu vượt 10.000; `lineNumber` là dòng vật lý của dòng dữ liệu thứ 10.001 |
| `MALFORMED_CSV` | File | Dấu `"` mở không được đóng trước khi hết file; `lineNumber` là dòng bắt đầu bản ghi lỗi [THIẾT KẾ] |
| `MISSING_COLUMN` | Header | Thiếu cột bắt buộc |
| `DUPLICATE_COLUMN` | Header | Cột xuất hiện hơn 1 lần |
| `UNKNOWN_COLUMN` | Header | Có cột không thuộc danh sách |
| `COLUMN_COUNT_MISMATCH` | Dòng | Số cột của dòng khác header |
| `REQUIRED_VALUE_MISSING` | Ô | Giá trị bắt buộc bị rỗng |
| `INVALID_NUMBER` | Ô | Không parse được thành số theo mục 3.3 |
| `OUT_OF_RANGE` | Ô | `quantity <= 0`, `unit_price < 0` hoặc `vat_rate < 0` |
| `TOO_MANY_DECIMALS` | Ô | `quantity` hoặc `unit_price` vượt số chữ số thập phân cho phép (không áp dụng cho `vat_rate`) |
| `VALUE_TOO_LONG` | Ô | `item_code` vượt 50 ký tự |

Ví dụ message: `Line 3, column 'quantity': value '-2' must be greater than 0 (OUT_OF_RANGE)`.

---

## 7. Yêu cầu phi chức năng

| Mã | Yêu cầu |
|---|---|
| NFR-1 | Java 17+ (Q13); build bằng Maven, sinh ra 1 file `.jar` |
| NFR-2 | Hạn chế phụ thuộc ngoài; nếu dùng thư viện CSV (vd. Apache Commons CSV) thì cung cấp thêm bản fat-jar hoặc ghi rõ dependency |
| NFR-3 | Xử lý file 10.000 dòng (mức tối đa) dưới 1 giây, heap không vượt quá 64 MB [THIẾT KẾ] |
| NFR-4 | Độ chính xác tiền tệ tuyệt đối: chỉ dùng `BigDecimal`, phép tính chính xác; chỉ làm tròn ở bước tính tổng |
| NFR-5 | Thread-safe, không dùng trạng thái static có thể thay đổi |
| NFR-6 | Test coverage ≥ 80% cho logic validate và tính toán |
| NFR-7 | Không log dữ liệu hoá đơn ra stdout; nếu log thì dùng SLF4J |

---

## 8. Tiêu chí nghiệm thu

| # | Kịch bản | Kết quả mong đợi |
|---|---|---|
| AC-1 | File hợp lệ như ví dụ mục 5.4 | Output giống hệt byte-by-byte với ví dụ |
| AC-2 | Header thiếu cột `vat_rate` | `MISSING_COLUMN`, không tạo file output |
| AC-3 | File chỉ có header | `NO_DATA_ROWS` |
| AC-4 | Dòng 3 có `quantity=abc`, dòng 5 có `vat_rate=-1` | Trả về **cả 2** lỗi (`INVALID_NUMBER` dòng 3, `OUT_OF_RANGE` dòng 5) |
| AC-5 | 1 dòng: `quantity=3, unit_price=3333, vat_rate=8` | Dòng: before `9999`, VAT `799.92`, after `10798.92`. TOTAL: VAT `799.9`, before `9999.0`, after `10798.9` |
| AC-6 | 2 dòng, mỗi dòng `quantity=1, unit_price=0.5, vat_rate=8` | VAT mỗi dòng `0.04` (không làm tròn). TOTAL VAT = round(0.08) = `0.1`. Nếu làm tròn từng dòng thì sẽ ra `0.0`, là **sai** |
| AC-7 | `vat_rate` = `150`, `0`, `12.345678` | Đều hợp lệ và được tính đúng công thức |
| AC-8 | `unit_price=0` | Hợp lệ, các cột tính toán = `0`, TOTAL = `0.0` |
| AC-9 | `quantity=0` hoặc âm | `OUT_OF_RANGE` |
| AC-10 | `item_code` chứa dấu phẩy, được đặt trong `"` | Đọc đúng, output vẫn đặt trong `"` |
| AC-11 | File input có BOM UTF-8 và kết thúc bằng dòng trống | Xử lý bình thường |
| AC-12 | File output đã tồn tại, input lỗi | File output cũ không bị thay đổi |
| AC-13 | 1 dòng: `quantity=1, unit_price=0.05, vat_rate=100` | Dòng: before `0.05`, VAT `0.05`, after `0.1`. TOTAL: before `0.1`, VAT `0.1`, after `0.1` (= round(0.1), **không** bằng before + VAT, đúng theo Q17) |
| AC-14 | Header viết hoa / có khoảng trắng, thứ tự cột đảo | Chấp nhận, output giữ thứ tự cột như input |
| AC-15 | Mọi file hợp lệ | Mọi dòng dữ liệu thoả `after = before + vat`; dòng TOTAL có đúng 1 chữ số thập phân |
| AC-16 | Đúng 10.000 dòng dữ liệu hợp lệ (có xen dòng trống) | Xử lý thành công, output có 10.000 dòng dữ liệu + TOTAL |
| AC-17 | 10.001 dòng dữ liệu | `TOO_MANY_ROWS`, không tạo file output |
| AC-18 | 50.000 dòng dữ liệu, dòng 20 có `quantity=abc` | Trả về `INVALID_NUMBER` dòng 20 và `TOO_MANY_ROWS`; thư viện không đọc quá dòng dữ liệu thứ 10.001 |
| AC-19 | 2 dòng cùng `item_code=SP001` | Hợp lệ, giữ nguyên 2 dòng riêng biệt trong output |

---

## 9. Quyết định nghiệp vụ (đã xác nhận)

| Mã | Câu hỏi | Quyết định |
|---|---|---|
| Q1 | Dấu phân cách | Dấu phẩy `,` |
| Q2 | Tên cột header | Tiếng Anh: `item_code,quantity,unit_price,vat_rate` |
| Q3 | Đơn giá đã gồm VAT chưa? | Chưa gồm VAT |
| Q4 | Số lượng có được là số lẻ không? | Được, tối đa 3 chữ số thập phân |
| Q5 | Miền giá trị `%vat` | Nhận mọi giá trị |
| Q6 | Trùng mã mặt hàng trong 1 hoá đơn? | Cho phép, không gộp dòng |
| Q7 | File chỉ có header? | Báo lỗi `NO_DATA_ROWS` |
| Q8 | Dừng ở lỗi đầu tiên hay trả về tất cả? | Trả về tất cả lỗi |
| Q9 | Làm tròn bao nhiêu chữ số? | 1 chữ số thập phân |
| Q9b | Chế độ làm tròn | `HALF_UP` |
| Q10 | Làm tròn theo dòng hay theo tổng? | Chỉ làm tròn trên tổng hoá đơn |
| Q11 | Output có BOM không? | Không BOM |
| Q12 | Nhãn dòng tổng, có tổng số lượng không? | `TOTAL`, không tính tổng số lượng |
| Q13 | Package, phiên bản Java | `com.ordercalc`, Java 17 |
| Q14 | Tên file output | Bên gọi truyền vào |
| Q15 | Giới hạn kích thước file | Tối đa 10.000 dòng dữ liệu |
| Q16 | `%vat` có nhận số âm không? | Không; nhận mọi số `>= 0` |
| Q17 | Cách tính tổng sau thuế | `round(Σ amount_after_tax)`, tính độc lập với 2 tổng còn lại |
