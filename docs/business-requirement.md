Cần xây dựng thư viện java có thể đóng gói thành gói *.jar
1. Input: file dịnh dạng csv có header. Các cột là: mã mặt hàng, số lượng, đơn giá, %vat. 1 file input tương ứng với 1 hoá đơn.
2. Output: file dịnh dạng csv có header. 
   - Ngoài các cột có sẵn của input, thêm các cột sau: thành tiền VAT, số tiền trước thuế, số tiền sau thuế.
   - Thêm 1 dòng ở cuối tính tổng 3 cột mới thêm vào.
3. Xử lý chính của function:
   - validate input. Dừng xử lý, trả về lỗi nếu cần thiết.
   - tính giá trị thành tiền VAT, số tiền trước thuế, số tiền sau thuế của từng dòng.
   - tính tổng 3 cột trên của hoá đơn này, cũng tức là file này. Lưu trị tổng vào cuối file.