package vn.nvest.savings.notification;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nvest.savings.common.CurrentUser;

@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "Thông báo của chính bạn")
public class NotificationController {

    public record NotificationResponse(Long id, String type, String message, Instant createdAt) {
    }

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return notificationService.list(CurrentUser.id(jwt), page, size).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.getCreatedAt()))
                .toList();
    }
}