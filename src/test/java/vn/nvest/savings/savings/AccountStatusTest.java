package vn.nvest.savings.savings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.nvest.savings.savings.AccountStatus.*;

class AccountStatusTest {

    @Test
    @DisplayName("R12: các đường chuyển hợp lệ")
    void allowedTransitions() {
        assertThat(ACTIVE.allowedNext()).containsExactlyInAnyOrder(MATURED, CLOSED_EARLY);
        assertThat(MATURED.allowedNext()).containsExactlyInAnyOrder(CLOSED, RENEWED);
    }

    @Test
    @DisplayName("Ba trạng thái cuối không đi tiếp được")
    void terminalStates() {
        assertThat(CLOSED.isTerminal()).isTrue();
        assertThat(RENEWED.isTerminal()).isTrue();
        assertThat(CLOSED_EARLY.isTerminal()).isTrue();
        assertThat(ACTIVE.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Không được nhảy cóc: ACTIVE không đi thẳng sang CLOSED hay RENEWED")
    void cannotSkipMatured() {
        assertThat(ACTIVE.canTransitionTo(CLOSED)).isFalse();
        assertThat(ACTIVE.canTransitionTo(RENEWED)).isFalse();
    }

    @Test
    @DisplayName("Không được quay ngược")
    void cannotGoBackwards() {
        assertThat(MATURED.canTransitionTo(ACTIVE)).isFalse();
        assertThat(CLOSED.canTransitionTo(ACTIVE)).isFalse();
        assertThat(CLOSED_EARLY.canTransitionTo(ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("Rút sớm chỉ từ ACTIVE, không từ MATURED")
    void earlyWithdrawalOnlyFromActive() {
        assertThat(ACTIVE.canTransitionTo(CLOSED_EARLY)).isTrue();
        assertThat(MATURED.canTransitionTo(CLOSED_EARLY)).isFalse();
    }
}
