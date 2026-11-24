package vn.nvest.savings.wallet;

import java.util.List;

/** Kết quả export: dữ liệu CSV và cờ cho biết có bị cắt bớt do vượt giới hạn hay không. */
public record TransactionExport(List<StatementLine> lines, boolean truncated) {
}
