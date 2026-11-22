package vn.nvest.savings.payment;

import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.PaymentAmountMismatchException;
import vn.nvest.savings.common.PaymentNotFoundException;

@RestController
@RequestMapping("/webhooks/vnpay")
@ConditionalOnProperty(name = "nvest.payment.provider", havingValue = "vnpay")
public class VnPayIpnController {

    private static final Logger log = LoggerFactory.getLogger(VnPayIpnController.class);

    private final VnPayGateway gateway;
    private final DepositService depositService;

    public VnPayIpnController(VnPayGateway gateway, DepositService depositService) {
        this.gateway = gateway;
        this.depositService = depositService;
    }

    /** VNPAY gọi GET. Luôn trả HTTP 200 kèm RspCode; RspCode khác 00/02 khiến VNPAY gọi lại. */
    @GetMapping("/ipn")
    public Map<String, String> ipn(@RequestParam Map<String, String> params) {
        if (!gateway.verifyParams(params)) {
            log.warn("Rejected VNPAY IPN: bad signature");
            return rsp("97", "Invalid signature");
        }
        if (!gateway.tmnCode().equals(params.get("vnp_TmnCode"))) {
            return rsp("99", "Unknown merchant");
        }
        try {
            long rawAmount = Long.parseLong(params.getOrDefault("vnp_Amount", "-1"));
            if (rawAmount <= 0 || rawAmount % 100 != 0) {
                return rsp("04", "Invalid amount");
            }
            boolean paid = "00".equals(params.get("vnp_ResponseCode"))
                    && "00".equals(params.get("vnp_TransactionStatus"));
            depositService.confirmFromGateway(
                    params.get("vnp_TxnRef"),
                    paid ? GatewayStatus.SUCCEEDED : GatewayStatus.FAILED,
                    rawAmount / 100,
                    params.get("vnp_TransactionNo"),
                    new TreeMap<>(params).toString());
            return rsp("00", "Confirm Success");   // gửi trùng cũng 00: confirmFromGateway không cộng hai lần (R28)
        } catch (PaymentNotFoundException e) {
            return rsp("01", "Order not found");
        } catch (PaymentAmountMismatchException e) {
            log.error("VNPAY IPN amount mismatch: {}", e.getMessage());
            return rsp("04", "Invalid amount");
        } catch (NumberFormatException e) {
            return rsp("99", "Malformed request");
        } catch (RuntimeException e) {
            log.error("VNPAY IPN failed", e);
            return rsp("99", "Unknown error");
        }
    }

    private static Map<String, String> rsp(String code, String message) {
        return Map.of("RspCode", code, "Message", message);
    }
}