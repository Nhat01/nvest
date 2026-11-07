package vn.nvest.savings.savings;

public record SettlementResponse(Long id, SettlementType type, MaturityOption optionApplied,
                                 long principal, long interest, Long newAccountId, String fallbackReason) {

    public static SettlementResponse from(Settlement settlement) {
        return new SettlementResponse(settlement.getId(), settlement.getSettlementType(),
                settlement.getOptionApplied(), settlement.getPrincipal(), settlement.getInterest(),
                settlement.getNewAccountId(), settlement.getFallbackReason());
    }
}