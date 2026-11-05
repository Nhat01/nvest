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
- [K. Tính lãi hàng ngày](#k-tính-lãi-hàng-ngày)
- [L. Quyết toán khi đáo hạn](#l-quyết-toán-khi-đáo-hạn)


## Tra cứu nhanh: mã quy tắc nằm ở mục nào

Các quy tắc được đánh số theo thứ tự ra đời, nhưng nằm trong mục theo **chủ đề**, nên số thứ tự không chạy liên tục từ đầu đến cuối file.

| Mã | Mục |
|---|---|
| R1–R3 | A. Tiền tệ và làm tròn |
| R4–R6 | B. Sản phẩm và lãi suất |
| R7–R11 | C. Cách tính lãi |
| R12–R15 | D. Vòng đời gói |
| R16–R21 | E. Đáo hạn và tái đầu tư |
| R22–R26, R51–R53 | F. Rút trước hạn |
| R27–R31, R54–R60 | G. Nạp và rút qua bên thứ ba |
| R32–R35 | H. Sổ cái |
| R36–R39 | I. Ràng buộc bổ sung từ thiết kế database |
| L1–L6 | J. Giới hạn đã biết |
| R40–R42 | K. Tính lãi hàng ngày |
| R43–R50 | L. Quyết toán khi đáo hạn |

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
| **R18** | Nếu sản phẩm đã ngừng bán, hoặc gốc tái tục nằm ngoài khoảng cho phép R3 của sản phẩm (vượt mức tối đa, hoặc dưới mức tối thiểu mới), hệ thống **tự chuyển sang `PAYOUT_ALL`** và ghi lý do vào `settlements.fallback_reason` để thông báo cho user. |
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
| **R51** | Rút trước hạn chỉ với gói `ACTIVE` và **trước** ngày đáo hạn, do chính chủ gói thực hiện. Từ ngày đáo hạn trở đi gói được quyết toán theo tùy chọn đáo hạn, không còn rút sớm. Số ngày đã gửi trọn vẹn tính bằng `hôm nay - start_date`. |
| **R52** | Gửi lại yêu cầu rút sớm cho gói đã rút sớm thì trả về biên lai cũ, không trả tiền lần hai. Chạy đồng thời với job đáo hạn thì chỉ một bên thắng: hoặc gói đã rút sớm (job bỏ qua), hoặc gói đã đáo hạn (rút sớm bị từ chối). |
| **R53** | Biên lai rút sớm lưu `settlement_type = EARLY_WITHDRAWAL` và `option_applied = PAYOUT_ALL`, không có gói mới. Các dòng lãi hàng ngày đã ghi được giữ nguyên làm lịch sử nhưng **không** dùng để tính tiền (R25). |

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
| **R54** | Mỗi yêu cầu tạo giao dịch nạp/rút có `idempotency_key` do client gửi (tối đa 80 ký tự). Gửi lại cùng key và cùng nội dung thì trả giao dịch cũ; cùng key khác nội dung thì bị từ chối. Nhiều request cùng key đến đồng thời được xếp hàng. |
| **R55** | Webhook sai chữ ký (hoặc thiếu chữ ký) bị từ chối, không đổi gì. Cổng là nguồn sự thật: webhook `SUCCEEDED` hợp lệ đến sau khi giao dịch nạp đã bị đánh dấu `FAILED` (thành công muộn) vẫn được cộng tiền; webhook `FAILED` đến sau khi đã `SUCCEEDED` bị bỏ qua. |
| **R56** | Số tiền trong webhook khác số tiền của đơn thì từ chối (không cộng tiền), giao dịch giữ nguyên trạng thái để đối soát. |
| **R57** | Mỗi lệnh rút tối thiểu 10.000đ, tối đa 100.000.000đ, và không vượt số dư khả dụng (R29). |
| **R58** | Gọi cổng chi tiền diễn ra **ngoài mọi transaction** cơ sở dữ liệu. Bước 1 (tạo lệnh và giữ tiền) và bước 2 (áp kết quả) là hai transaction riêng, để timeout hay lỗi mạng không làm mất lệnh giữ tiền. Nếu bước 2 lỗi, lệnh vẫn `PENDING` và tiền vẫn bị giữ, job đối soát xử lý sau. |
| **R59** | Chỉ trả lại tiền (release) khi cổng báo thất bại **rõ ràng**. Mọi lỗi khác khi gọi cổng (timeout, mất kết nối, ngoại lệ không rõ) đều đưa lệnh sang `UNKNOWN` và giữ nguyên tiền (R30). Chỉ xác nhận (confirm) khi cổng báo thành công và số tiền khớp. |
| **R60** | Job đối soát chạy mỗi 5 phút: hỏi lại cổng các lệnh nạp `PENDING` quá 30 phút (R31) và các lệnh rút `PENDING`/`UNKNOWN` quá 5 phút. Với lệnh rút, cổng báo không có dấu vết (`NOT_FOUND`) chỉ được coi là thất bại khi lệnh đã quá 2 giờ; trước đó giữ nguyên `UNKNOWN`. |

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
| **R38** | Mở gói bắt buộc có `idempotency_key` do client gửi (tối đa 80 ký tự). Gửi lại cùng key và cùng nội dung thì trả về gói đã mở, không trừ tiền lần hai. Cùng key nhưng khác số tiền, user hoặc sản phẩm thì bị từ chối. Nhiều request cùng key đến **đồng thời** được xếp hàng và đều nhận về cùng một gói. | `ux_savings_accounts_idempotency` + advisory lock theo key + kiểm tra trong `SavingsService` |
| **R39** | "Ngày" nghiệp vụ (ngày mở gói, ngày đáo hạn, ngày tính lãi) luôn tính theo múi giờ `Asia/Ho_Chi_Minh`. | `Clock` cấu hình ở `ClockConfig` |

Ghi chú: ví của user không bao giờ âm (`ck_wallets_user_no_overdraft`), nhưng ví nền tảng được phép âm vì chúng đại diện cho dòng tiền vào/ra hệ thống.

## J. Giới hạn đã biết

| Mã | Giới hạn | Khi nào cần xử lý |
|---|---|---|
| **L1** | Các ví hệ thống (`EXTERNAL`, `CUSTODY`, `INTEREST_POOL`) bị khóa bởi mọi giao dịch liên quan, nên các giao dịch đó được xử lý tuần tự. | Khi **đo được** độ trễ do xếp hàng ở các ví này. Hướng xử lý: chia nhỏ ví hệ thống, hoặc không lưu số dư ở ví hệ thống. |
| **L2** | Không được nạp entity `Wallet` trước khi gọi `LedgerService.post` trong cùng một transaction, vì bản entity trong bộ nhớ đệm sẽ cũ so với dòng đã khóa. Chỉ lấy **id** ví trước khi gọi `post`. Lưu ý `WalletService.getBalance` **có** nạp entity: không gọi nó bên trong transaction sẽ ghi tiền; chỉ cần kiểm tra ví tồn tại thì dùng `WalletService.requireWallet`. | Luôn áp dụng. |
| **L3** | Chống mở trùng đồng thời dùng khóa advisory của PostgreSQL (`pg_advisory_xact_lock(hashtext(key))`), nên gắn chặt với Postgres. Hai key khác nhau có thể trùng mã băm 32 bit, khi đó chỉ bị xếp hàng nhầm với nhau chứ không sai kết quả. | Khi đổi sang database khác. |
| **L4** | Tạo phiên thanh toán nạp tiền gọi cổng ngay trong transaction cơ sở dữ liệu. Với cổng giả thì không sao; với cổng thật nên tách thành hai bước (lưu đơn, rồi gọi cổng ngoài transaction) để không giữ kết nối DB trong lúc chờ mạng. | Khi chuyển sang cổng thật. |
| **L5** | Cổng báo thành công nhưng số tiền khác đơn thì hệ thống chỉ từ chối và ghi log, chưa có quy trình xử lý thủ công hay cảnh báo. | Khi có kênh vận hành/cảnh báo. |
| **L6** | Lệnh rút `UNKNOWN` mà cổng báo không có dấu vết sau 2 giờ bị tự động trả tiền về ví. Nếu cổng thật xử lý lệnh đó rất muộn (hiếm gặp), user có thể nhận tiền hai lần. | Khi dùng cổng thật: thêm quy trình duyệt thủ công và cảnh báo. |

## K. Tính lãi hàng ngày

| Mã | Quy tắc |
|---|---|
| **R40** | Job tính lãi chạy mỗi ngày lúc 00:05 (giờ Việt Nam) và ghi lãi cho các ngày **đã kết thúc**, tức đến hết hôm qua. Lãi của ngày thứ n (ngày `start_date + n - 1`) chỉ được ghi sau khi ngày đó kết thúc. |
| **R41** | Nếu job lỡ nhiều ngày, lần chạy sau **ghi bù** đủ các ngày còn thiếu theo đúng thứ tự. Mỗi gói có tối đa `term_days` dòng lãi, không bao giờ ghi quá kỳ hạn. |
| **R42** | Mỗi gói được xử lý trong transaction riêng và khóa dòng gói. Chạy lặp lại hoặc nhiều instance chạy đồng thời đều không ghi trùng; một gói lỗi không chặn các gói khác (R20). |

Hệ quả: số ngày đã ghi lãi của một gói bằng số ngày đã gửi trọn vẹn, nên khớp với cách tính lãi rút trước hạn (R23, R24).

## L. Quyết toán khi đáo hạn

| Mã | Quy tắc |
|---|---|
| **R43** | Lãi trả khi đáo hạn tính bằng công thức R8 cho toàn kỳ hạn, và phải **bằng tổng lãi đã ghi hàng ngày** (R10). Nếu lệch, quyết toán gói đó bị hủy và báo lỗi, thay vì trả sai tiền. Trước khi quyết toán, hệ thống ghi nốt các ngày lãi còn thiếu. |
| **R44** | Gói tái tục bắt đầu vào **đúng ngày đáo hạn của gói cũ** (không phải ngày job chạy), có `cycle_no + 1`, `parent_id` trỏ về gói cũ, và kế thừa tùy chọn đáo hạn của gói cũ. Lãi suất và kỳ hạn lấy theo sản phẩm hiện hành (R17). |
| **R45** | Tiền khi quyết toán di chuyển trong **một giao dịch sổ cái duy nhất**: `PAYOUT_ALL` trả gốc (từ `CUSTODY`) và lãi (từ `INTEREST_POOL`) cho user; `RENEW_PRINCIPAL` giữ gốc ở `CUSTODY`, trả lãi cho user; `RENEW_ALL` giữ gốc ở `CUSTODY` và chuyển lãi từ `INTEREST_POOL` sang `CUSTODY` để nhập vào gốc mới. |
| **R46** | Quyết toán mà không có đồng nào di chuyển (lãi bằng 0 và tái tục) thì không ghi sổ cái, `settlements.ledger_transaction_id` để trống. |
| **R47** | Chỉ quyết toán gói đang `ACTIVE` và đã đến hạn (`maturity_date <= hôm nay`). Gói đã quyết toán hoặc chưa đến hạn bị bỏ qua, nên chạy lặp hoặc chạy đồng thời đều không trả tiền hai lần. |
| **R48** | Đổi tùy chọn đáo hạn (R16) chỉ được với gói `ACTIVE`, do chính chủ gói thực hiện, và chỉ **trước** ngày đáo hạn. Thao tác khóa dòng gói nên không đụng độ với job đáo hạn: tùy chọn nào được chốt trước thì được áp dụng, không bao giờ bị mất. |
| **R49** | Job đáo hạn chạy mỗi ngày lúc 00:05 và có thể lặp nhiều lượt trong một lần chạy (tối đa 10), để xử lý cả những gói tái tục mà bản thân chúng cũng đã đến hạn do job lỡ nhiều kỳ (R19). Gói đã thử một lần trong lần chạy thì không thử lại, tránh vòng lặp vô hạn khi có gói lỗi. |
| **R50** | Quy ước khóa để tránh deadlock: mọi thao tác ghi tiền khóa **dòng gói (hoặc dòng giao dịch thanh toán) trước, rồi các ví theo id tăng dần**. |

---

## Số liệu mẫu dùng cho test

| Tình huống | Đầu vào | Kết quả |
|---|---|---|
| Lãi đủ kỳ | 10.000.000đ, 6%, 90 ngày | **147.945đ** |
| Rút sớm | 10.000.000đ, 0,5%, 40 ngày | **5.479đ** |
| Kỳ 2 của `RENEW_ALL` | 10.147.945đ, 6%, 90 ngày | **150.133đ** |
| Rút sớm sau 10 ngày | 10.000.000đ, 0,5% | **1.369đ** |
| Lãi ngày 1 và ngày 2 | 10.000.000đ, 6% | **1.643đ** và **1.644đ** |
| Tổng lãi 10 ngày đầu | 10.000.000đ, 6% | **16.438đ** |
| Gói 5.000.000đ, 4,5%, 30 ngày | `FLEX_30` | **18.493đ** |
| Gói 500.000.000đ, 6%, 90 ngày | `SAVE_90` | **7.397.260đ** |
| Gốc kỳ 3 của `RENEW_ALL` | 10.147.945đ + 150.133đ | **10.298.078đ** |