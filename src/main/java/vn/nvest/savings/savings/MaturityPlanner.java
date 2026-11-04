package vn.nvest.savings.savings;

import vn.nvest.savings.wallet.SettlementTransfer;

/** Quyết định cách quyết toán một gói đáo hạn. Không đụng DB, không có tác dụng phụ. */
final class MaturityPlanner {

    static final String REASON_PRODUCT_NOT_ON_SALE = "PRODUCT_NOT_ON_SALE";
    static final String REASON_AMOUNT_OUT_OF_RANGE = "AMOUNT_OUT_OF_RANGE";

    record Plan(MaturityOption applied, String fallbackReason, long newPrincipal, SettlementTransfer transfer) {
    }

    private MaturityPlanner() {
    }

    static Plan plan(MaturityOption requested, SavingsProduct product, long principal, long interest) {
        MaturityOption applied = requested;
        String fallbackReason = null;
        long newPrincipal = 0;

        if (requested != MaturityOption.PAYOUT_ALL) {
            long candidate = requested == MaturityOption.RENEW_ALL
                    ? Math.addExact(principal, interest)
                    : principal;
            if (!product.isOnSale()) {                       // R18
                applied = MaturityOption.PAYOUT_ALL;
                fallbackReason = REASON_PRODUCT_NOT_ON_SALE;
            } else if (!product.isAmountAllowed(candidate)) {   // R18
                applied = MaturityOption.PAYOUT_ALL;
                fallbackReason = REASON_AMOUNT_OUT_OF_RANGE;
            } else {
                newPrincipal = candidate;
            }
        }

        SettlementTransfer transfer = switch (applied) {
            case PAYOUT_ALL -> new SettlementTransfer(principal, interest, 0);
            case RENEW_PRINCIPAL -> new SettlementTransfer(0, interest, 0);
            case RENEW_ALL -> new SettlementTransfer(0, 0, interest);
        };
        return new Plan(applied, fallbackReason, newPrincipal, transfer);
    }
}