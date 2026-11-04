package vn.nvest.savings.savings;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.interest.InterestCalculator;
import vn.nvest.savings.wallet.LedgerTransaction;
import vn.nvest.savings.wallet.WalletService;

@Service
public class MaturityService {

    private final SavingsAccountRepository accountRepository;
    private final SavingsProductRepository productRepository;
    private final SettlementRepository settlementRepository;
    private final InterestAccrualService accrualService;
    private final WalletService walletService;

    public MaturityService(SavingsAccountRepository accountRepository,
                           SavingsProductRepository productRepository,
                           SettlementRepository settlementRepository,
                           InterestAccrualService accrualService,
                           WalletService walletService) {
        this.accountRepository = accountRepository;
        this.productRepository = productRepository;
        this.settlementRepository = settlementRepository;
        this.accrualService = accrualService;
        this.walletService = walletService;
    }

    /**
     * Quyết toán một gói đáo hạn trong MỘT transaction: trả tiền, đổi trạng thái, tạo gói mới nếu tái tục.
     *
     * @return kết quả quyết toán, hoặc rỗng nếu gói không còn ACTIVE hoặc chưa đến hạn (R47)
     */
    @Transactional
    public Optional<Settlement> settle(long accountId, LocalDate asOf) {
        // Khóa trước, đọc sau (R26, R47)
        SavingsAccount account = accountRepository.findByIdForUpdate(accountId).orElse(null);
        if (account == null
                || account.getStatus() != AccountStatus.ACTIVE
                || account.getMaturityDate().isAfter(asOf)) {
            return Optional.empty();
        }

        // 1) Ghi nốt lãi còn thiếu, rồi đối chiếu với công thức (R43)
        accrualService.accrueAccount(accountId, account.getMaturityDate().minusDays(1));
        long interest = InterestCalculator.accruedInterest(
                account.getPrincipal(), account.getAnnualRate(), account.getTermDays());
        long accrued = accrualService.getAccruedInterest(accountId);
        if (accrued != interest) {
            throw new IllegalStateException("Account " + accountId + ": accrued interest " + accrued
                    + " does not match formula " + interest + " (R43)");
        }

        // 2) Quyết định (R17, R18)
        SavingsProduct product = productRepository.findById(account.getProductId())
                .orElseThrow(() -> new IllegalStateException("Product missing for account " + accountId));
        MaturityPlanner.Plan plan = MaturityPlanner.plan(
                account.getMaturityOption(), product, account.getPrincipal(), interest);

        // 3) Chuyển tiền trong một giao dịch sổ cái (R45, R46)
        Optional<LedgerTransaction> ledgerTx = walletService.transferForSettlement(
                account.getUserId(), plan.transfer(), "settle:" + accountId, "SAVINGS_ACCOUNT", accountId);

        // 4) Đổi trạng thái, tạo gói mới nếu tái tục (R12, R44)
        account.transitionTo(AccountStatus.MATURED);
        Long newAccountId = null;
        if (plan.applied() == MaturityOption.PAYOUT_ALL) {
            account.transitionTo(AccountStatus.CLOSED);
        } else {
            SavingsAccount next = accountRepository.save(
                    SavingsAccount.renewFrom(account, product, plan.newPrincipal()));
            account.transitionTo(AccountStatus.RENEWED);
            newAccountId = next.getId();
        }

        // 5) Biên lai quyết toán
        Settlement settlement = settlementRepository.save(new Settlement(
                accountId, SettlementType.MATURITY, plan.applied(),
                account.getPrincipal(), interest, newAccountId,
                ledgerTx.map(LedgerTransaction::getId).orElse(null), plan.fallbackReason()));
        return Optional.of(settlement);
    }
}