package vn.nvest.savings.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionCsvExporterTest {

    @Test
    @DisplayName("Bọc ngoặc kép cho trường có dấu phẩy, ngoặc kép hoặc xuống dòng")
    void escapesSpecialCharacters() {
        assertThat(TransactionCsvExporter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(TransactionCsvExporter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(TransactionCsvExporter.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(TransactionCsvExporter.escape("plain")).isEqualTo("plain");
        assertThat(TransactionCsvExporter.escape(null)).isEmpty();
        assertThat(TransactionCsvExporter.escape("")).isEmpty();
    }

    @Test
    @DisplayName("CSV có header cố định và một dòng cho mỗi bút toán")
    void writesHeaderAndRows() {
        StatementLine line = new StatementLine(
                7L, LedgerTxType.DEPOSIT, BalanceType.AVAILABLE, 500_000,
                "PAYMENT", 42L, "Deposit, first", Instant.parse("2026-01-02T03:04:05Z"));

        byte[] csv = TransactionCsvExporter.toCsv(List.of(line));
        String text = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(text).startsWith("entryId,txType,balanceType,amount,referenceType,referenceId,description,createdAt\n");
        assertThat(text).contains("7,DEPOSIT,AVAILABLE,500000,PAYMENT,42,\"Deposit, first\",2026-01-02T03:04:05Z");
    }
}
