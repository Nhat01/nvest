# Nvest: Quy tắc nghiệp vụ (v1)

Tài liệu gốc của dự án. Mọi code và test phải tham chiếu theo mã quy tắc (R1, R2, ...).

> Dự án học tập, dùng **tiền ảo** và cổng thanh toán giả lập. Không dùng cho tiền thật.

## Mục lục

- [A. Tiền tệ và làm tròn](#a-tiền-tệ-và-làm-tròn)
- [B. Sản phẩm và lãi suất](#b-sản-phẩm-và-lãi-suất)
- [C. Cách tính lãi](#c-cách-tính-lãi)
- [D. Vòng đời gói](#d-vòng-đời-gói)
- [E. Đáo hạn và tái đầu tư](#e-đáo-hạn-và-tái-đầu-tư)
- [F. Rút trước hạn](#f-rút-trước-hạn)
- [G. Nạp và rút qua bên thứ ba](#g-nạp-và-rút-qua-bên-thứ-ba)
- [H. Sổ cái](#h-sổ-cái)
- [I. Ràng buộc bổ sung từ thiết kế database](#i-ràng-buộc-bổ-sung-từ-thiết-kế-database)
- [J. Giới hạn đã biết](#j-giới-hạn-đã-biết)

---

## A. Tiền tệ và làm tròn

| Mã | Quy tắc |
|---|---|
| **R1** | Chỉ dùng VND, không có số lẻ. Lưu trong DB dạng `BIGINT` (đơn vị đồng), trong Java dùng `BigDecimal` khi tính toán. |
| **R2** | Mọi phép tính lãi làm tròn **xuống** (`RoundingMode.DOWN`), có lợi cho nền tảng. |
| **R3** | Số tiền gửi tối thiểu **1.000.000đ**, tối đa **500.000.000đ** mỗi gói. |

## B. Sản phẩm và lãi suất

**R4.** Ba sản phẩm ban đầu:

| Mã | Kỳ hạn | Lãi suất/năm |
|---|---|---|
| `FLEX_30` | 30 ngày | 4,5% |
| `SAVE_90` | 90 ngày | 6,0% |
| `LONG_180` | 180 ngày | 7,0% |

| Mã | Quy tắc |
|---|---|
| **R5** | Lãi suất **chốt vào gói tại thời điểm mở** và không đổi trong suốt kỳ đó, dù admin sửa lãi suất sản phẩm sau này. |
| **R6** | Lãi suất không kỳ hạn (dùng khi rút trước hạn): **0,5%/năm**. |

## C. Cách tính lãi

| Mã | Quy tắc |
|---|---|
| **R7** | Lãi đơn trong mỗi kỳ. Lãi kép chỉ xảy ra gián tiếp qua `RENEW_ALL` (lãi nhập vào gốc kỳ sau). |
| **R8** | `lãi = gốc × lãi suất năm × số ngày / 365`. Quy ước chia 365 cho mọi năm, không xét năm nhuận. |
| **R9** | Gói mở ngày `D` thì **ngày đáo hạn = D + kỳ hạn**. Lãi tính cho các ngày từ `D` đến `D + kỳ hạn - 1`, tức đúng `kỳ hạn` ngày. Ngày mở tính, ngày đáo hạn không tính. |
| **R10** | Lãi ghi mỗi ngày bằng cách **tính lũy kế rồi lấy hiệu** (công thức bên dưới). |
| **R11** | Lãi **chưa trả vào ví** cho đến khi đáo hạn. Trong lúc chờ chỉ là "lãi tạm tính" để hiển thị. |

**Công thức R10:**

```text
lãi_lũy_kế(n) = floor(gốc × lãi suất × n / 365)
lãi_ngày_n    = lãi_lũy_kế(n) - lãi_lũy_kế(n-1)
```

Cách này đảm bảo tổng các ngày **khớp tuyệt đối** với công thức R8, không lệch đồng nào.

**Ví dụ:** gốc 10.000.000đ, lãi suất 6%, 90 ngày.

- Lãi tổng = **147.945đ**
- Mỗi ngày nhận 1.643đ hoặc 1.644đ tùy làm tròn lũy kế, cộng lại đúng 147.945đ

## D. Vòng đời gói

| Mã | Quy tắc |
|---|---|
| **R12** | Trạng thái: `ACTIVE` → `MATURED` → `CLOSED` hoặc `RENEWED`. Ngoài ra `ACTIVE` → `CLOSED_EARLY`. |
| **R13** | Mở gói: trừ tiền khỏi ví **và** tạo gói **trong cùng một transaction**. Ví không đủ tiền thì từ chối. |
| **R14** | Không sửa số tiền hay kỳ hạn của gói đã mở. Muốn khác thì rút trước hạn rồi mở gói mới. |
| **R15** | Một user có thể mở nhiều gói cùng lúc. |

```text
ACTIVE ──► MATURED ──► CLOSED     (nhận gốc + lãi về ví)
   │           └────► RENEWED     (tạo gói mới, cycle_no + 1)
   └──► CLOSED_EARLY              (rút trước hạn)
```

## E. Đáo hạn và tái đầu tư

**R16.** Lúc mở gói, user chọn một trong ba tùy chọn và **được đổi bất cứ lúc nào trước ngày đáo hạn**:

| Tùy chọn | Kết quả |
|---|---|
| `PAYOUT_ALL` | Gốc + lãi về ví, gói `CLOSED` |
| `RENEW_PRINCIPAL` | Gốc mở gói mới, lãi về ví, gói cũ `RENEWED` |
| `RENEW_ALL` | Gốc + lãi thành gốc gói mới, gói cũ `RENEWED` |

| Mã | Quy tắc |
|---|---|
| **R17** | Gói tái tục **cùng sản phẩm**, dùng **lãi suất hiện hành** của sản phẩm tại ngày đáo hạn, `cycle_no + 1`. |
| **R18** | Nếu sản phẩm đã ngừng bán, hoặc gốc tái tục vượt mức tối đa R3, hệ thống **tự chuyển sang `PAYOUT_ALL`** và thông báo cho user. |
| **R19** | Job đáo hạn chạy mỗi ngày lúc 00:05. Nếu lỡ nhiều ngày, lần chạy sau **xử lý bù tất cả gói có ngày đáo hạn ≤ hôm nay**. Lãi vẫn tính theo đúng ngày đáo hạn thực. |
| **R20** | Mỗi gói xử lý trong transaction riêng. Gói lỗi được ghi lại để thử lại, không chặn các gói khác. |
| **R21** | Đáo hạn rơi vào cuối tuần hoặc lễ vẫn xử lý bình thường (hệ thống mô phỏng, không phụ thuộc lịch ngân hàng). |

## F. Rút trước hạn

| Mã | Quy tắc |
|---|---|
| **R22** | Chỉ rút trước hạn **toàn bộ** gói, không rút một phần. |
| **R23** | Hoàn đủ gốc. Lãi chỉ tính theo lãi suất không kỳ hạn R6 cho số ngày đã gửi: `lãi = floor(gốc × 0,5% × số ngày đã gửi / 365)` |
| **R24** | Gửi chưa đủ 1 ngày thì lãi = 0. |
| **R25** | Khi rút trước hạn, các khoản accrual cũ được **bỏ qua**, lãi thực trả chỉ theo R23. Gói chuyển `CLOSED_EARLY`. |
| **R26** | Gói đang được job đáo hạn xử lý thì thao tác rút sớm bị từ chối hoặc chờ (khóa dòng, kiểm tra lại status). |

**Ví dụ:** gửi 10.000.000đ được 40 ngày rồi rút.

- Lãi thực nhận = **5.479đ**
- Nếu đủ hạn 40 ngày ở mức 6% thì được khoảng 65.753đ

## G. Nạp và rút qua bên thứ ba

| Mã | Quy tắc |
|---|---|
| **R27** | Nạp tối thiểu 10.000đ, tối đa 100.000.000đ/lần. Tiền chỉ vào ví khi nhận xác nhận hợp lệ (webhook đã verify chữ ký, số tiền khớp). |
| **R28** | Mỗi giao dịch có mã duy nhất. Webhook gửi trùng thì **chỉ xử lý một lần**, các lần sau trả OK nhưng không cộng thêm. |
| **R29** | Rút: phải đủ số dư khả dụng. Hệ thống **giữ (hold)** tiền trước, gọi cổng chi, thành công thì trừ hẳn, thất bại rõ ràng thì trả lại. |
| **R30** | Kết quả mơ hồ (timeout) → trạng thái `UNKNOWN`, tiền vẫn bị giữ, job đối soát hỏi lại cổng. **Không bao giờ tự trả lại tiền khi chưa biết chắc.** |
| **R31** | Giao dịch nạp `PENDING` quá 30 phút thì job đối soát hỏi lại cổng. Cổng báo thất bại hoặc không có thì đánh dấu `FAILED`. |

## H. Sổ cái

| Mã | Quy tắc |
|---|---|
| **R32** | Mọi biến động tiền là một dòng `LedgerEntry` **bất biến** (không sửa, không xóa; sai thì ghi bút toán đảo). |
| **R33** | Số dư ví = tổng các dòng ledger, không có chỗ nào sửa số dư trực tiếp. |
| **R34** | Tiền lãi trả cho user lấy từ **ví nền tảng** (`PLATFORM_INTEREST_POOL`). Mỗi giao dịch ghi hai chiều: ví nền tảng giảm, ví user tăng. |
| **R35** | Mọi thao tác ghi tiền có `idempotency_key`. Cùng key thì chỉ thực hiện một lần. |

## I. Ràng buộc bổ sung từ thiết kế database

Hai quy tắc này phát sinh khi thiết kế bảng ở Bước 3 và được database bảo vệ trực tiếp.

| Mã | Quy tắc | Được bảo vệ bởi |
|---|---|---|
| **R36** | Lãi suất sản phẩm nằm trong khoảng **0% đến 20%/năm**. Admin nhập sai (ví dụ 60%) thì bị từ chối. | API validation + `ck_products_rate` |
| **R37** | Hệ thống có đúng 3 ví nền tảng: `PLATFORM_EXTERNAL` (đại diện thế giới bên ngoài, được phép âm), `PLATFORM_SAVINGS_CUSTODY` (giữ gốc các gói đang chạy), `PLATFORM_INTEREST_POOL` (nguồn trả lãi). Mỗi giao dịch ledger có **tổng các dòng bằng 0**. | `ux_wallets_system_type` + kiểm tra trong service và test |
| **R38** | Mở gói bắt buộc có `idempotency_key` do client gửi (tối đa 80 ký tự). Gửi lại cùng key và cùng nội dung thì trả về gói đã mở, không trừ tiền lần hai. Cùng key nhưng khác số tiền, user hoặc sản phẩm thì bị từ chối. | `ux_savings_accounts_idempotency` + kiểm tra trong `SavingsService` |
| **R39** | "Ngày" nghiệp vụ (ngày mở gói, ngày đáo hạn, ngày tính lãi) luôn tính theo múi giờ `Asia/Ho_Chi_Minh`. | `Clock` cấu hình ở `ClockConfig` |

Ghi chú: ví của user không bao giờ âm (`ck_wallets_user_no_overdraft`), nhưng ví nền tảng được phép âm vì chúng đại diện cho dòng tiền vào/ra hệ thống.

## J. Giới hạn đã biết

| Mã | Giới hạn | Khi nào cần xử lý |
|---|---|---|
| **L1** | Các ví hệ thống (`EXTERNAL`, `CUSTODY`, `INTEREST_POOL`) bị khóa bởi mọi giao dịch liên quan, nên các giao dịch đó được xử lý tuần tự. | Khi **đo được** độ trễ do xếp hàng ở các ví này. Hướng xử lý: chia nhỏ ví hệ thống, hoặc không lưu số dư ở ví hệ thống. |
| **L2** | Không được nạp entity `Wallet` trước khi gọi `LedgerService.post` trong cùng một transaction, vì bản entity trong bộ nhớ đệm sẽ cũ so với dòng đã khóa. Chỉ lấy **id** ví trước khi gọi `post`. | Luôn áp dụng. |

---

## Số liệu mẫu dùng cho test

| Tình huống | Đầu vào | Kết quả |
|---|---|---|
| Lãi đủ kỳ | 10.000.000đ, 6%, 90 ngày | **147.945đ** |
| Rút sớm | 10.000.000đ, 0,5%, 40 ngày | **5.479đ** |
| Kỳ 2 của `RENEW_ALL` | 10.147.945đ, 6%, 90 ngày | **150.133đ** |