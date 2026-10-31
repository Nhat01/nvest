package vn.nvest.savings.wallet;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.nvest.savings.common.InsufficientFundsException;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "wallets")
public class Wallet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_type", nullable = false)
    private WalletType walletType;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "available_balance", nullable = false)
    private long availableBalance;

    @Column(name = "held_balance", nullable = false)
    private long heldBalance;

    @Version
    private long version;

    private Wallet(WalletType walletType, Long userId) {
        this.walletType = walletType;
        this.userId = userId;
    }

    public static Wallet forUser(Long userId) {
        return new Wallet(WalletType.USER, userId);
    }

    public static Wallet system(WalletType type) {
        if (type == WalletType.USER) {
            throw new IllegalArgumentException("Use forUser() for user wallets");
        }
        return new Wallet(type, null);
    }

    /** Cộng (amount > 0) hoặc trừ (amount < 0) vào một ngăn số dư. Ví user không được âm. */
    public void apply(BalanceType balanceType, long amount) {
        long current = balanceOf(balanceType);
        long updated = Math.addExact(current, amount);
        if (walletType == WalletType.USER && updated < 0) {
            throw new InsufficientFundsException("Wallet " + id + ": " + balanceType
                    + " balance " + current + " is not enough for change " + amount);
        }
        if (balanceType == BalanceType.AVAILABLE) {
            availableBalance = updated;
        } else {
            heldBalance = updated;
        }
    }

    public long balanceOf(BalanceType balanceType) {
        return balanceType == BalanceType.AVAILABLE ? availableBalance : heldBalance;
    }
}
