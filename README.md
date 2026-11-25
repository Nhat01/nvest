# savings-platform

Backend học tập mô phỏng một nền tảng gửi tiết kiệm: ví điện tử, nạp/rút tiền,
gói tiết kiệm có kỳ hạn, tính lãi theo ngày, tất toán và nhắc đến hạn.

## Công nghệ

- Java 21, Spring Boot 3.5
- PostgreSQL 16, Flyway
- Spring Security + JWT
- Spring Kafka, outbox pattern
- Testcontainers cho test tích hợp
- Maven

## Yêu cầu

- JDK 21
- Docker (để chạy PostgreSQL, Kafka và test tích hợp)
- Maven 3.9+

## Chạy local

Khởi động PostgreSQL và Kafka:

```bash
docker compose up -d
```

Chạy ứng dụng:

```bash
# Linux/macOS
export NVEST_ALLOW_DEV_JWT_SECRET=true
mvn spring-boot:run

# PowerShell
$env:NVEST_ALLOW_DEV_JWT_SECRET="true"
mvn spring-boot:run
```

Biến `NVEST_ALLOW_DEV_JWT_SECRET=true` chỉ dùng khi chạy local với khóa JWT mặc
định. Khi triển khai thật phải set `NVEST_JWT_SECRET` dài ít nhất 32 byte và bỏ
biến này.

Địa chỉ sau khi chạy:

| Mục | URL |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

Các biến môi trường khác xem trong `.env.example`. Spring Boot không tự đọc file
`.env`, phải export biến trước khi chạy hoặc cấu hình trong IDE.

## Chạy test

```bash
mvn verify
```

Test tích hợp dùng Testcontainers, tự khởi động `postgres:16` và
`apache/kafka:3.9.0`. Lần chạy đầu sẽ tải image nên chậm hơn.

## Docker

Build image:

```bash
docker build -t savings-platform .
```

Chạy container, trỏ vào PostgreSQL đang chạy trên máy host:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/savings \
  -e SPRING_DATASOURCE_USERNAME=savings \
  -e SPRING_DATASOURCE_PASSWORD=savings \
  -e NVEST_KAFKA_ENABLED=false \
  -e NVEST_ALLOW_DEV_JWT_SECRET=true \
  savings-platform
```

Trên Linux, thêm `--add-host=host.docker.internal:host-gateway` để container
gọi được máy host.

Image chạy bằng user thường (uid 1001) và nhận các biến sau:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PORT` | `8080` | Cổng HTTP, dùng được với PaaS |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError` | Tham số JVM |

`NVEST_KAFKA_ENABLED=false` tắt toàn bộ phần Kafka (relay, topic, consumer).
Khi bật Kafka, cần thêm `KAFKA_BOOTSTRAP_SERVERS`.

## CI

`.github/workflows/ci.yml` chạy khi push lên `master`/`main`, khi mở pull
request, hoặc bấm tay:

- Job `test`: `mvn -B -ntp verify` trên JDK 21, dùng Testcontainers.
- Job `docker`: build image để kiểm tra Dockerfile, không push lên registry.

Khi test fail, báo cáo Surefire được upload thành artifact trong 7 ngày.

Dependabot (`.github/dependabot.yml`) kiểm tra Maven, GitHub Actions và Docker
hàng tuần.

## Cấu trúc chính

```text
src/main/java/vn/nvest/savings
├── user          đăng ký, đăng nhập, JWT
├── wallet        ví, giao dịch, xuất CSV
├── savings       sản phẩm, gói tiết kiệm, lãi, tất toán
├── interest      công thức tính lãi
├── payment       cổng thanh toán (mock, VNPAY), đối soát
├── notification  thông báo qua Kafka
├── outbox        outbox pattern, publish sự kiện
└── common        cấu hình, xử lý lỗi, tiện ích
```

## Tài liệu

Quy tắc nghiệp vụ: [docs/business-rules.md](docs/business-rules.md)
