package vn.nvest.savings.wallet;

/** Ba luồng tiền khi quyết toán một gói tiết kiệm. Mỗi số là một khoản chuyển (>= 0). */
public record SettlementTransfer(long custodyToUser, long poolToUser, long poolToCustody) {

    public SettlementTransfer {
        if (custodyToUser < 0 || poolToUser < 0 || poolToCustody < 0) {
            throw new IllegalArgumentException("Transfer amounts must not be negative");
        }
    }

    /** True nếu không có đồng nào di chuyển (R46). */
    public boolean isEmpty() {
        return custodyToUser == 0 && poolToUser == 0 && poolToCustody == 0;
    }
}
