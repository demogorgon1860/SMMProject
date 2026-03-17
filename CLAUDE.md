# CLAUDE.md - SMM Panel Development Guide

**CRITICAL**: This file provides comprehensive guidance to Claude Code for working with this SMM Panel codebase.

## Project Overview

SMM Panel for Instagram marketing services. **Core architecture: Panel (Spring Boot + React) + Instagram Bot (Go).**

## Architecture

- **Frontend**: React + TypeScript SPA (Vite, Tailwind CSS, Zustand)
- **Backend**: Spring Boot 3.1.7 (JPA, Redis caching, RabbitMQ messaging, JWT auth)
- **Database**: PostgreSQL with Liquibase migrations
- **Instagram Bot**: Go bot at `C:\Users\user\Desktop\instagramBot` (rod + AdsPower)
- **Communication**: Bot sends results via RabbitMQ + HTTP webhook to Panel
- **Cache**: Redis for performance optimization
- **Container Orchestration**: Docker Compose

### Production Server
- **IP**: `45.142.211.90`
- **SSH login**: `Админ`
- **Panel path**: `C:\SMMPanel`
- **PostgreSQL container**: `smm_postgres` (user: `smm_admin`, db: `smm_panel`)

## Development Commands

### Backend (Gradle)

```bash
cd backend && ./gradlew build              # Build
cd backend && ./gradlew clean build        # Clean build (fixes Lombok issues)
cd backend && ./gradlew test               # Run tests
cd backend && ./gradlew test --tests "ClassName"  # Specific test
cd backend && ./gradlew bootRun            # Run dev mode
cd backend && ./gradlew spotlessApply      # Format code
cd backend && ./gradlew liquibaseUpdate    # Database migration (LIQUIBASE ONLY!)
```

### Frontend

```bash
cd frontend && npm install      # Install deps
cd frontend && npm run dev      # Dev server
cd frontend && npm run build    # Production build
cd frontend && npm test         # Tests
cd frontend && npm run lint:fix # Fix linting
cd frontend && npm run type-check  # TypeScript check
```

### Docker

```bash
docker-compose -f docker-compose.dev.yml up -d    # Dev environment
docker-compose up -d                               # Production
docker-compose up -d --build spring-boot-app       # Rebuild backend
docker-compose logs -f spring-boot-app             # View logs
docker-compose down                                # Stop all
```

### Database

```bash
docker-compose exec postgres psql -U smm_admin -d smm_panel  # Access DB
cd backend && ./gradlew liquibaseUpdate                       # Run migrations
```

## CRITICAL WARNINGS

### 1. Database Migrations: LIQUIBASE ONLY
- USE: `backend/src/main/resources/db/changelog/`
- IGNORE: `backend/src/main/resources/db/migration/` (legacy Flyway, NOT used)
- Master file: `db.changelog-master.xml`
- NEVER run `./gradlew flywayMigrate` or `flywayValidate`

### 2. Lombok Compilation
If "cannot find symbol" errors: `cd backend && ./gradlew clean build`

### 3. Redis Health Indicator Disabled
- `RedisHealthIndicator.java` is DISABLED (renamed to `.disabled`)
- Redis uses Lettuce client, NOT Jedis

### 4. Two Result Paths from Instagram Bot
Bot results arrive through TWO paths - both must be kept in sync:
1. **RabbitMQ**: `InstagramResultConsumer.java` - consumes from RabbitMQ queue
2. **HTTP Webhook**: `InstagramService.java` - receives POST callbacks

Both paths handle: order status updates, refund logic (full + partial), charge recalculation.

### 5. Telegram Notification System (Native Java, no n8n)
Telegram integration is fully in-house — **no n8n, no external webhook forwarder**.

#### Architecture
```
OrderService / InstagramService / InstagramResultConsumer
        │
        ▼
TelegramNotificationService   ← thin facade, zero business logic
        │
        ▼
TelegramBotService            ← sends messages via Telegram Bot API directly
    ├── CancelDecisionService  ← Redis-backed pending decision store
    └── DailyProfitService     ← Redis counters + PostgreSQL persistence
```

#### Key classes (`service/notification/`)
| Class | Role |
|-------|------|
| `TelegramNotificationService` | Facade called by all order-processing code. Never bypass this. |
| `TelegramBotService` | Core: sends messages, inline keyboards, edits messages via `https://api.telegram.org/bot{token}/...` |
| `CancelDecisionService` | Stores `CancelPendingDecision` in Redis (`telegram:cancel_pending:{orderId}`) with SETNX + TTL |
| `DailyProfitService` | Increments Redis hash (`telegram:profit:{date}`) per COMPLETED/PARTIAL order; persists to `daily_profit_summary` at end of day |
| `TelegramUpdateHandler` | Processes Telegram `callback_query` updates from inline buttons |

#### Cancel Approval Flow
When the Instagram bot fails/cancels an order, the panel does **not** auto-cancel. Instead:
1. `TelegramBotService.notifyOrderCancelledPending()` sends admin a message with two inline buttons:
   - `✅ Продолжить` → `callback_data: cancel_proceed:{orderId}` — order continues unchanged
   - `❌ Отменить` → `callback_data: cancel_do:{orderId}` — full refund + status = CANCELLED
2. Decision is stored in Redis with 4-hour TTL (`app.telegram.cancel.timeout-hours`)
3. Admin clicks a button → Telegram sends POST to `/api/telegram/webhook` → `TelegramWebhookController` → `TelegramUpdateHandler.process()`
4. On **proceed**: removes Redis key, removes inline keyboard, logs decision
5. On **cancel**: full refund via `BalanceService.refund()`, status → `CANCELLED`, `trafficStatus` → `CANCELLED_BY_ADMIN`
6. On **timeout** (scheduler, every 10 min): applies `app.telegram.cancel.default-action` (`proceed` or `cancel`), status → `CANCELLED_TIMEOUT` if auto-cancelled

#### Daily Profit Report
- Scheduler (`TelegramScheduler`) fires at `23:55` cron
- Counts COMPLETED + PARTIAL orders accumulated in Redis during the day
- Sends formatted report to admin chat, then persists to `daily_profit_summary` table
- Redis keys: `telegram:profit:{yyyy-MM-dd}` (hash: `total`, `completed_count`, `partial_count`), TTL = 8 days

#### Webhook registration
`TelegramWebhookRegistrar` runs on startup (`ApplicationReadyEvent`) and calls Telegram `setWebhook` API to register `/api/telegram/webhook` as the incoming update URL.

#### Config (`application.yml` → `app.telegram.*`)
```yaml
app:
  telegram:
    enabled: true
    bot:
      token: ${TELEGRAM_BOT_TOKEN}
      chat-id: ${TELEGRAM_CHAT_ID}
      webhook-secret: ${TELEGRAM_WEBHOOK_SECRET:}
    cancel:
      timeout-hours: 4
      default-action: proceed   # proceed | cancel
    profit:
      redis-ttl-days: 8
```

#### Notification events
| Method on TelegramNotificationService | When called | Profit recorded? |
|---------------------------------------|-------------|-----------------|
| `notifyNewOrder(order)` | Order created | No |
| `notifyOrderCompleted(order, completed)` | Status → COMPLETED | Yes (COMPLETED counter) |
| `notifyOrderPartial(order, completed)` | Status → PARTIAL | Yes (PARTIAL counter) |
| `notifyOrderFailed(order, completed)` | Status → FAILED | No |
| `notifyOrderCancelledPending(order)` | Bot reports cancel | No (pending decision) |

#### IMPORTANT — what NOT to do
- Do NOT call `TelegramBotService` directly from order-processing code — always go through `TelegramNotificationService`
- Do NOT add a second `telegram:` key in `application.yml` — it already exists under `app:` (caused startup crash before)
- Do NOT record profit for FAILED or CANCELLED orders — only COMPLETED and PARTIAL

## API Structure

- REST API base path: `/api`
- Current version: `/api/v1/`
- Perfect Panel compatible: `/api/v2/`
- Authentication: JWT Bearer token or `X-API-Key` header
- Full docs: `API_DOCUMENTATION.md`

### Key Endpoints

| Category | Endpoint | Description |
|----------|----------|-------------|
| Auth | `POST /api/v1/auth/login` | User login |
| Auth | `POST /api/v1/auth/register` | User registration |
| Orders | `POST /api/v1/orders` | Create new order |
| Orders | `GET /api/v1/orders` | Get user orders |
| Balance | `GET /api/v1/balance` | Get current balance |
| Services | `GET /api/v1/service/services` | List all services |
| Admin | `GET /api/v2/admin/dashboard` | Admin dashboard stats |
| Perfect Panel | `POST /api/v2?action=add` | Perfect Panel compatible order |
| Webhook | `POST /api/webhook/instagram` | Instagram bot callback |

## Instagram Bot Integration

### Bot API (`http://45.142.211.90:8080`)

```http
POST /api/orders/create
{
  "type": "like|comment|follow|like_follow|like_comment|like_comment_follow",
  "target_url": "https://instagram.com/p/XXX/",
  "count": 100,
  "external_id": "panel_order_12345",
  "callback_url": "https://your-panel.com/api/webhook/instagram"
}
```

### Webhook Callback Format

```json
{
  "event": "order.completed",
  "id": "order_xxx",
  "external_id": "panel_order_12345",
  "status": "completed|failed",
  "completed": 95,
  "failed": 5,
  "start_like_count": 100,
  "current_like_count": 195,
  "start_follower_count": 5000,
  "current_follower_count": 5095
}
```

### Bot Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/orders/create` | POST | Create order |
| `/api/orders` | GET | List all orders |
| `/api/orders/get?id=X` | GET | Get order by ID |
| `/api/orders/cancel` | POST | Cancel order |
| `/api/orders/stats` | GET | Queue statistics |
| `/api/orders/workers` | POST | Start/stop workers |
| `/api/health` | GET | Health check |

### Bot Codebase (`C:\Users\user\Desktop\instagramBot`)

- **Language**: Go 1.21+
- **Browser Automation**: rod (Chrome DevTools Protocol)
- **Antidetect**: AdsPower integration
- **AI Comments**: OpenAI GPT-4o Vision
- Key files: `cmd/bot/main.go`, `internal/orders/manager.go`, `internal/api/`, `internal/bot/`

### Bot Profile Groups (AdsPower)

| Group | Purpose |
|-------|---------|
| `Success` | Profiles for executing actions (likes, follows, comments) |
| `Start_count` | Scout profiles for counting initial likes/comments/followers |

## Key Files & Directories

### Backend
```
backend/src/main/java/com/smmpanel/
├── config/           # Configuration classes
├── controller/       # REST endpoints
├── service/         # Business logic
│   └── integration/ # InstagramService (webhook path)
├── consumer/        # RabbitMQ consumers (InstagramResultConsumer)
├── repository/      # JPA repositories
├── entity/          # JPA entities
└── dto/             # Data transfer objects
```

### Frontend
```
frontend/src/
├── components/      # React components
├── services/        # API service layer
├── stores/          # Zustand state management
└── types/           # TypeScript interfaces
```

## Common Workflows

### Deploying to Production
```bash
# On local machine
git add <files> && git commit -m "message" && git push

# On server (SSH to 45.142.211.90)
cd C:\SMMPanel
git pull
docker-compose up -d --build spring-boot-app
```

### Adding a New REST Endpoint
1. Create DTO in `dto/` package
2. Add controller method
3. Implement service logic
4. Add repository methods if needed
5. Write tests

### Adding Database Migration
1. Create Liquibase XML in `backend/src/main/resources/db/changelog/changes/`
2. Add include to `db.changelog-master.xml`
3. Run `./gradlew liquibaseUpdate`

## Environment Variables (Production)

```bash
DB_PASSWORD=xxx
DB_HOST=localhost
DB_PORT=5432
REDIS_PASSWORD=xxx
JWT_SECRET=xxx
CRYPTOMUS_API_KEY=xxx
CRYPTOMUS_API_SECRET=xxx
```

## Best Practices

- Always use Liquibase for database changes
- Run `./gradlew clean build` after pulling
- Keep both Instagram result paths (RabbitMQ + webhook) in sync
- Use DTOs for API communication
- Check logs when debugging: `docker-compose logs -f spring-boot-app`
- NEVER use Flyway commands
- NEVER commit secrets or API keys
