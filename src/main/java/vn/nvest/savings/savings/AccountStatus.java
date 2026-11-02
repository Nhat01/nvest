package vn.nvest.savings.savings;

import java.util.EnumSet;
import java.util.Set;

/** Trạng thái gói và các đường chuyển hợp lệ (R12). */
public enum AccountStatus {
    ACTIVE, MATURED, CLOSED, RENEWED, CLOSED_EARLY;

    public Set<AccountStatus> allowedNext() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(MATURED, CLOSED_EARLY);
            case MATURED -> EnumSet.of(CLOSED, RENEWED);
            case CLOSED, RENEWED, CLOSED_EARLY -> EnumSet.noneOf(AccountStatus.class);
        };
    }

    public boolean canTransitionTo(AccountStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
