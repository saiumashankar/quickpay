# QuickPay backend

The backend contains the service registry, three Spring Boot microservices, and the
Docker Compose stack that runs them together.

| Folder | Responsibility | Local port |
|---|---|---:|
| `eureka-server/` | Service registry | 8761 |
| `auth-service/` | User accounts, authentication, and notification preferences | 8081 |
| `payment-service/` | Wallets, payments, and event publishing | 8082 |
| `notification-service/` | Payment event consumption and email delivery | 8083 |

## Run the complete stack

From the repository root:

```bash
docker compose -f backend/docker-compose.yml up -d --build
```

Mailpit's inbox is available at <http://localhost:8025>. See the root
[README](../README.md) for architecture, environment settings, and the end-to-end flow.
