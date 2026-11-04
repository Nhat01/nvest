package vn.nvest.savings.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.nvest.savings.wallet.SettlementTransfer;

class MaturityPlannerTest {

    private static final long PRINCIPAL = 10_000_000;
    private static final long INTEREST = 147_945;

    private static SavingsProduct save90() {
        return new SavingsProduct("SAVE_90", "Tich luy 90 ngay", 90,
                new BigDecimal("0.0600"), 1_000_000, 500_000_000);
    }

    @Test
    @DisplayName("PAYOUT_ALL: gốc từ custody và lãi từ pool đều về user, không có gói mới")
    void payoutAll() {
        MaturityPlanner.Plan plan = MaturityPlanner.plan(MaturityOption.PAYOUT_ALL, save90(), PRINCIPAL, INTEREST);

        assertThat(plan.applied()).isEqualTo(MaturityOption.PAYOUT_ALL);
        assertThat(plan.fallbackReason()).isNull();
        assertThat(plan.newPrincipal()).isZero();
        assertThat(plan.transfer()).isEqualTo(new SettlementTransfer(PRINCIPAL, INTEREST, 0));
    }

    @Test
    @DisplayName("RENEW_PRINCIPAL: gốc ở lại, lãi về user, gói mới có gốc cũ")
    void renewPrincipal() {
        MaturityPlanner.Plan plan = MaturityPlanner.plan(MaturityOption.RENEW_PRINCIPAL, save90(), PRINCIPAL, INTEREST);

        assertThat(plan.applied()).isEqualTo(MaturityOption.RENEW_PRINCIPAL);
        assertThat(plan.newPrincipal()).isEqualTo(PRINCIPAL);
        assertThat(plan.transfer()).isEqualTo(new SettlementTransfer(0, INTEREST, 0));
    }

    @Test
    @DisplayName("RENEW_ALL: lãi chuyển sang custody nhập vào gốc, gói mới có gốc + lãi")
    void renewAll() {
        MaturityPlanner.Plan plan = MaturityPlanner.plan(MaturityOption.RENEW_ALL, save90(), PRINCIPAL, INTEREST);

        assertThat(plan.applied()).isEqualTo(MaturityOption.RENEW_ALL);
        assertThat(plan.newPrincipal()).isEqualTo(10_147_945);
        assertThat(plan.transfer()).isEqualTo(new SettlementTransfer(0, 0, INTEREST));
    }

    @Test
    @DisplayName("R18: sản phẩm đã ngừng bán thì tự chuyển sang PAYOUT_ALL")
    void fallbackWhenProductStopped() {
        SavingsProduct product = save90();
        product.stopSale();

        MaturityPlanner.Plan plan = MaturityPlanner.plan(MaturityOption.RENEW_ALL, product, PRINCIPAL, INTEREST);

        assertThat(plan.applied()).isEqualTo(MaturityOption.PAYOUT_ALL);
        assertThat(plan.fallbackReason()).isEqualTo(MaturityPlanner.REASON_PRODUCT_NOT_ON_SALE);
        assertThat(plan.newPrincipal()).isZero();
        assertThat(plan.transfer()).isEqualTo(new SettlementTransfer(PRINCIPAL, INTEREST, 0));
    }

    @Test
    @DisplayName("R18: gốc + lãi vượt mức tối đa thì chuyển sang PAYOUT_ALL, nhưng chỉ tái tục gốc vẫn được")
    void fallbackWhenAmountExceedsMax() {
        long maxPrincipal = 500_000_000;
        long interest = 7_397_260;

        MaturityPlanner.Plan renewAll = MaturityPlanner.plan(MaturityOption.RENEW_ALL, save90(), maxPrincipal, interest);
        MaturityPlanner.Plan renewPrincipal = MaturityPlanner.plan(MaturityOption.RENEW_PRINCIPAL, save90(), maxPrincipal, interest);

        assertThat(renewAll.applied()).isEqualTo(MaturityOption.PAYOUT_ALL);
        assertThat(renewAll.fallbackReason()).isEqualTo(MaturityPlanner.REASON_AMOUNT_OUT_OF_RANGE);
        assertThat(renewPrincipal.applied()).isEqualTo(MaturityOption.RENEW_PRINCIPAL);
        assertThat(renewPrincipal.newPrincipal()).isEqualTo(maxPrincipal);
    }

    @Test
    @DisplayName("Bảo toàn tiền: gốc còn trong custody sau quyết toán luôn bằng gốc của gói mới")
    void custodyKeepsExactlyTheNewPrincipal() {
        for (MaturityOption option : MaturityOption.values()) {
            MaturityPlanner.Plan plan = MaturityPlanner.plan(option, save90(), PRINCIPAL, INTEREST);
            SettlementTransfer t = plan.transfer();

            long custodyAfter = PRINCIPAL - t.custodyToUser() + t.poolToCustody();

            assertThat(custodyAfter).as("option %s", option).isEqualTo(plan.newPrincipal());
            assertThat(t.custodyToUser() + t.poolToUser() + t.poolToCustody())
                    .as("tổng lãi + gốc luôn được tính đủ, option %s", option)
                    .isEqualTo(plan.applied() == MaturityOption.PAYOUT_ALL ? PRINCIPAL + INTEREST : INTEREST);
        }
    }
}