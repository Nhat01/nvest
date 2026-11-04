package vn.nvest.savings.savings;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nvest.savings.common.AccountNotFoundException;
import vn.nvest.savings.common.InvalidStateTransitionException;
import vn.nvest.savings.interest.InterestCalculator;
import vn.nvest.savings.wallet.LedgerTransaction;
import vn.nvest.savings.wallet.LedgerTxType;
import vn.nvest.savings.wallet.SettlementTransfer;
import vn.nvest.savings.wallet.WalletService;

@Service
public class EarlyWithdrawalService {

    private final SavingsAccountRepository accountRepository;
    private final SettlementRepository settlementRepository;
    private final WalletService walletService;
    private final Clock clock;

    public EarlyWithdrawalService(SavingsAccountRepository accountRepository,
                                  SettlementRepository settlementRepository,
                                  WalletService walletService,
                                  Clock clock) {
        this.accountRepository = accountRepository;
        this.settlementRepository = settlementRepository;
        this.walletService = walletService;
        this.clock = clock;
    }

    /**
     * Rút trước hạn toàn bộ gói (R22): hoàn đủ gốc, lãi theo lãi suất không kỳ hạn
     * cho số ngày đã gửi trọn vẹn (R23, R24). Gửi lại thì trả biên lai cũ (R52).
     */
    @Transactional
    public Settlement withdraw(long userId, long accountId) {
        // Khóa trước, đọc sau (R26). Gói của người khác coi như không tồn tại.
        SavingsAccount account = accountRepository.findByIdForUpdate(accountId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        // Đã rút sớm rồi: trả biên lai cũ, không trả tiền lần hai (R52)
        if (account.getStatus() == AccountStatus.CLOSED_EARLY) {
            return settlementRepository.findByAccountId(accountId).orElseThrow(() ->
                    new IllegalStateException("Account " + accountId + " is CLOSED_EARLY but has no settlement"));
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidStateTransitionException("Account " + accountId + " is "
                    + account.getStatus() + ": only ACTIVE accounts can be withdrawn early (R51)");
        }
        LocalDate today = LocalDate.now(clock);
        if (!today.isBefore(account.getMaturityDate())) {
            throw new InvalidStateTransitionException("Account " + accountId
                    + " has reached its maturity date and will be settled at maturity (R51)");
        }

        // R23, R24: lãi không kỳ hạn cho số ngày đã gửi trọn vẹn
        int daysHeld = (int) ChronoUnit.DAYS.between(account.getStartDate(), today);
        long interest = InterestCalculator.earlyWithdrawalInterest(account.getPrincipal(), daysHeld);

        // Một giao dịch sổ cái: gốc từ custody và lãi từ pool về ví user
        Optional<LedgerTransaction> ledgerTx = walletService.transferForSettlement(
                userId, new SettlementTransfer(account.getPrincipal(), interest, 0),
                LedgerTxType.EARLY_WITHDRAWAL, "early:" + accountId, "SAVINGS_ACCOUNT", accountId);

        account.transitionTo(AccountStatus.CLOSED_EARLY);

        return settlementRepository.save(new Settlement(
                accountId, SettlementType.EARLY_WITHDRAWAL, MaturityOption.PAYOUT_ALL,
                account.getPrincipal(), interest, null,
                ledgerTx.map(LedgerTransaction::getId).orElse(null), null));
    }
}