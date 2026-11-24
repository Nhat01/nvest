package vn.nvest.savings.wallet;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Chuyển các dòng sao kê thành CSV. Tự bọc ngoặc kép cho trường có dấu phẩy, ngoặc kép hoặc xuống dòng. */
public final class TransactionCsvExporter {

    private static final String HEADER =
            "entryId,txType,balanceType,amount,referenceType,referenceId,description,createdAt";

    private TransactionCsvExporter() {
    }

    public static byte[] toCsv(List<StatementLine> lines) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (StatementLine line : lines) {
            csv.append(line.entryId()).append(',')
                    .append(line.txType()).append(',')
                    .append(line.balanceType()).append(',')
                    .append(line.amount()).append(',')
                    .append(escape(line.referenceType())).append(',')
                    .append(line.referenceId() == null ? "" : line.referenceId()).append(',')
                    .append(escape(line.description())).append(',')
                    .append(line.createdAt() == null ? ""
                            : DateTimeFormatter.ISO_INSTANT.format(line.createdAt()))
                    .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
