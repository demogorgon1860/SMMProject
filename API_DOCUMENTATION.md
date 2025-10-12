# SMM Panel API Documentation

## Table of Contents
- [Overview](#overview)
- [Authentication](#authentication)
- [Base URLs](#base-urls)
- [Rate Limiting](#rate-limiting)
- [Error Handling](#error-handling)
- [API Endpoints](#api-endpoints)
  - [Authentication API](#authentication-api)
  - [Orders API](#orders-api)
  - [Balance API](#balance-api)
  - [Services API](#services-api)
  - [Admin API](#admin-api)
  - [API Keys API](#api-keys-api)
  - [Perfect Panel Compatibility API](#perfect-panel-compatibility-api)
  - [Webhooks API](#webhooks-api)
  - [Monitoring APIs](#monitoring-apis)

---

## Overview

The SMM Panel API is a comprehensive RESTful API for managing social media marketing services. Built with Spring Boot 3.1.7, it provides robust endpoints for order management, payment processing, user authentication, and service automation.

### Key Features
- JWT-based authentication with refresh tokens
- API key authentication for external integrations
- Rate limiting per user/endpoint
- Perfect Panel API compatibility
- Real-time WebSocket support for order updates
- Comprehensive admin dashboard APIs
- Kafka-based asynchronous processing
- Redis caching for performance optimization

### Technology Stack
- **Backend**: Spring Boot 3.1.7, Java 17+
- **Database**: PostgreSQL with Liquibase migrations
- **Cache**: Redis with Lettuce client
- **Message Queue**: Apache Kafka
- **Authentication**: JWT tokens, API keys
- **Documentation**: OpenAPI 3.0 (Swagger)
- **Monitoring**: Prometheus, Grafana, Jaeger

---

## Authentication

The API supports two authentication methods:

### 1. JWT Authentication
- Used for web application and mobile clients
- Tokens expire after configured duration (default: 24 hours)
- Refresh tokens supported with HttpOnly cookies

### 2. API Key Authentication
- Used for external integrations and Perfect Panel compatibility
- Pass key via `X-API-Key` header or `key` query parameter
- No expiration, but can be rotated manually

### Authentication Headers
```http
# JWT Authentication
Authorization: Bearer <jwt_token>

# API Key Authentication
X-API-Key: <api_key>
```

---

## Base URLs

### Environment-specific URLs
- **Development**: `http://localhost:8080/api`
- **Production**: `https://api.your-domain.com/api`

### API Versioning
- **Current Version**: `/api/v1/` - Main API endpoints
- **Perfect Panel Compatibility**: `/api/v2/` - Perfect Panel compatible endpoints

---

## Rate Limiting

Rate limiting is implemented per user and endpoint type:

| Endpoint Type | Requests/Minute | Requests/Hour |
|--------------|----------------|---------------|
| Authentication | 10 | 60 |
| Order Creation | 30 | 300 |
| Balance Operations | 20 | 200 |
| Admin Operations | 100 | 1000 |
| Read Operations | 100 | 5000 |

Rate limit headers:
- `X-RateLimit-Limit`: Maximum requests allowed
- `X-RateLimit-Remaining`: Requests remaining
- `X-RateLimit-Reset`: Unix timestamp when limit resets

---

## Error Handling

### Standard Error Response
```json
{
  "error": "Error message",
  "code": 400,
  "timestamp": "2024-01-01T12:00:00Z",
  "path": "/api/v1/orders",
  "details": {
    "field": "Additional error details"
  }
}
```

### HTTP Status Codes
| Code | Description |
|------|-------------|
| 200 | Success |
| 201 | Created |
| 204 | No Content |
| 400 | Bad Request - Validation error |
| 401 | Unauthorized - Authentication required |
| 402 | Payment Required - Insufficient balance |
| 403 | Forbidden - Insufficient permissions |
| 404 | Not Found |
| 429 | Too Many Requests - Rate limit exceeded |
| 500 | Internal Server Error |
| 503 | Service Unavailable |

---

## API Endpoints

### Authentication API

#### 1. Register New User
```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "username": "string",
  "email": "user@example.com",
  "password": "string",
  "confirmPassword": "string"
}
```

**Response:**
```json
{
  "id": 1,
  "username": "string",
  "email": "user@example.com",
  "role": "USER",
  "token": "jwt_token",
  "refreshToken": "refresh_token",
  "tokenType": "Bearer",
  "expiresIn": 86400
}
```

#### 2. Login
```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "string",
  "password": "string"
}
```

**Response:**
```json
{
  "id": 1,
  "username": "string",
  "email": "user@example.com",
  "role": "USER",
  "token": "jwt_token",
  "refreshToken": "refresh_token",
  "tokenType": "Bearer",
  "expiresIn": 86400
}
```

#### 3. Refresh Token
```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
  "refreshToken": "refresh_token"
}
```

**Response:**
```json
{
  "accessToken": "new_jwt_token",
  "refreshToken": "new_refresh_token"
}
```

#### 4. Get Current User
```http
GET /api/v1/auth/me
Authorization: Bearer <token>
```

**Response:**
```json
{
  "id": 1,
  "username": "string",
  "email": "user@example.com",
  "role": "USER",
  "balance": "100.00"
}
```

#### 5. Get User Profile
```http
GET /api/v1/auth/profile
Authorization: Bearer <token>
```

**Response:**
```json
{
  "id": 1,
  "username": "string",
  "email": "user@example.com",
  "role": "USER",
  "balance": "100.00",
  "isActive": true,
  "hasApiKey": false,
  "apiKeyLastRotated": null,
  "createdAt": null,
  "updatedAt": null
}
```

#### 6. Logout
```http
POST /api/v1/auth/logout
Authorization: Bearer <token>
```

**Response:** `200 OK`

#### 7. Logout All Devices
```http
POST /api/v1/auth/logout-all
Authorization: Bearer <token>
```

**Response:** `200 OK`

---

### Orders API

#### 1. Create Order
```http
POST /api/v1/orders
Authorization: Bearer <token>
Content-Type: application/json

{
  "service": 1,
  "link": "https://youtube.com/watch?v=xxx",
  "quantity": 1000
}
```

**Response:**
```json
{
  "data": {
    "id": 12345,
    "service": 1,
    "link": "https://youtube.com/watch?v=xxx",
    "quantity": 1000,
    "status": "PENDING",
    "charge": "10.00",
    "startCount": 0,
    "remains": 1000,
    "createdAt": "2024-01-01T12:00:00Z"
  },
  "success": true,
  "message": "Order created successfully"
}
```

#### 2. Get User Orders
```http
GET /api/v1/orders?status=ACTIVE&page=0&size=20&sort=createdAt&direction=desc
Authorization: Bearer <token>
```

**Query Parameters:**
- `status` (optional): PENDING, IN_PROGRESS, PROCESSING, ACTIVE, PARTIAL, COMPLETED, CANCELLED, PAUSED, HOLDING, REFILL
- `page` (default: 0): Page number (0-based)
- `size` (default: 20, max: 100): Page size
- `sort` (default: createdAt): Sort field (id, createdAt, updatedAt, status, quantity)
- `direction` (default: desc): Sort direction (asc, desc)

**Response:**
```json
{
  "data": {
    "content": [
      {
        "id": 12345,
        "service": 1,
        "link": "https://youtube.com/watch?v=xxx",
        "quantity": 1000,
        "status": "ACTIVE",
        "charge": "10.00",
        "startCount": 100,
        "remains": 900,
        "createdAt": "2024-01-01T12:00:00Z"
      }
    ],
    "totalElements": 100,
    "totalPages": 5,
    "number": 0,
    "size": 20
  },
  "success": true,
  "message": "Orders retrieved successfully"
}
```

#### 3. Get Order by ID
```http
GET /api/v1/orders/{id}
Authorization: Bearer <token>
```

**Response:**
```json
{
  "data": {
    "id": 12345,
    "service": 1,
    "link": "https://youtube.com/watch?v=xxx",
    "quantity": 1000,
    "status": "ACTIVE",
    "charge": "10.00",
    "startCount": 100,
    "remains": 900,
    "createdAt": "2024-01-01T12:00:00Z",
    "updatedAt": "2024-01-01T13:00:00Z"
  },
  "success": true,
  "message": "Order retrieved successfully"
}
```

#### 4. Cancel Order
```http
POST /api/v1/orders/{id}/cancel
Authorization: Bearer <token>
```

**Response:**
```json
{
  "success": true,
  "message": "Order cancelled successfully"
}
```

#### 5. Get Order Statistics (Operator/Admin)
```http
GET /api/v1/orders/stats?days=30
Authorization: Bearer <token>
```

**Response:**
```json
{
  "data": {
    "totalOrders": 1000,
    "activeOrders": 250,
    "completedOrders": 700,
    "cancelledOrders": 50,
    "totalRevenue": "10000.00",
    "averageOrderValue": "10.00",
    "ordersByStatus": {
      "PENDING": 50,
      "ACTIVE": 250,
      "COMPLETED": 700
    },
    "dailyStats": [
      {
        "date": "2024-01-01",
        "orders": 35,
        "revenue": "350.00"
      }
    ]
  },
  "success": true,
  "message": "Statistics retrieved successfully"
}
```

#### 6. Bulk Order Operations (Admin)
```http
POST /api/v1/orders/bulk
Authorization: Bearer <token>
Content-Type: application/json

{
  "orderIds": [1, 2, 3],
  "action": "CANCEL",
  "reason": "Bulk cancellation"
}
```

**Response:**
```json
{
  "data": {
    "processed": 3,
    "succeeded": 3,
    "failed": 0,
    "errors": []
  },
  "success": true,
  "message": "Bulk operation completed"
}
```

#### 7. Order Service Health Check
```http
GET /api/v1/orders/health
```

**Response:**
```json
{
  "data": {
    "status": "UP",
    "healthy": true,
    "components": {
      "database": "UP",
      "kafka": "UP",
      "redis": "UP"
    }
  },
  "success": true,
  "message": "Service healthy"
}
```

---

### Balance API

#### 1. Get Current Balance
```http
GET /api/v1/balance
Authorization: Bearer <token>
```

**Response:**
```json
{
  "balance": "100.00",
  "currency": "USD",
  "lastUpdated": "2024-01-01T12:00:00Z"
}
```

#### 2. Add Funds (Deposit)
```http
POST /api/v1/balance/deposit
Authorization: Bearer <token>
Content-Type: application/json

{
  "amount": "50.00",
  "description": "Payment via Cryptomus"
}
```

**Response:**
```json
{
  "success": true,
  "newBalance": "150.00",
  "message": "Funds added successfully"
}
```

#### 3. Get Transaction History
```http
GET /api/v1/balance/transactions?page=0&size=20
Authorization: Bearer <token>
```

**Response:**
```json
{
  "content": [
    {
      "id": 1,
      "type": "DEPOSIT",
      "amount": "50.00",
      "balanceBefore": "100.00",
      "balanceAfter": "150.00",
      "description": "Payment via Cryptomus",
      "createdAt": "2024-01-01T12:00:00Z"
    },
    {
      "id": 2,
      "type": "CHARGE",
      "amount": "-10.00",
      "balanceBefore": "150.00",
      "balanceAfter": "140.00",
      "description": "Order #12345",
      "createdAt": "2024-01-01T13:00:00Z"
    }
  ],
  "totalElements": 50,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

#### 4. Get Recent Transactions
```http
GET /api/v1/balance/transactions/recent
Authorization: Bearer <token>
```

**Response:** Returns last 10 transactions in same format as transaction history

#### 5. Check Sufficient Funds
```http
POST /api/v1/balance/check-funds
Authorization: Bearer <token>
Content-Type: application/json

{
  "amount": "25.00"
}
```

**Response:**
```json
{
  "hasSufficientFunds": true,
  "currentBalance": "140.00",
  "requiredAmount": "25.00"
}
```

---

### Services API

#### 1. Get All Active Services
```http
GET /api/v1/service/services
```

**Response:**
```json
{
  "data": [
    {
      "id": 1,
      "name": "YouTube Views",
      "category": "YouTube",
      "type": "Default",
      "rate": "10.00",
      "min": 100,
      "max": 10000,
      "description": "High quality YouTube views",
      "dripfeed": false,
      "refill": true,
      "cancel": true
    },
    {
      "id": 2,
      "name": "YouTube Likes",
      "category": "YouTube",
      "type": "Default",
      "rate": "15.00",
      "min": 10,
      "max": 5000,
      "description": "Real YouTube likes",
      "dripfeed": false,
      "refill": true,
      "cancel": true
    }
  ],
  "success": true
}
```

---

### Admin API

All admin endpoints require `ADMIN` or `OPERATOR` role.

#### 1. Get Dashboard Statistics
```http
GET /api/v2/admin/dashboard
Authorization: Bearer <token>
```

**Response:**
```json
{
  "totalUsers": 1000,
  "activeUsers": 750,
  "totalOrders": 5000,
  "activeOrders": 250,
  "totalRevenue": "50000.00",
  "todayRevenue": "1500.00",
  "systemHealth": {
    "database": "UP",
    "kafka": "UP",
    "redis": "UP"
  }
}
```

#### 2. Get All Orders (Admin View)
```http
GET /api/v2/admin/orders?status=ACTIVE&username=user1&dateFrom=2024-01-01&dateTo=2024-01-31&page=0&size=20
Authorization: Bearer <token>
```

**Response:**
```json
{
  "content": [
    {
      "id": 12345,
      "username": "user1",
      "service": "YouTube Views",
      "link": "https://youtube.com/watch?v=xxx",
      "quantity": 1000,
      "status": "ACTIVE",
      "charge": "10.00",
      "startCount": 100,
      "remains": 900,
      "createdAt": "2024-01-01T12:00:00Z"
    }
  ],
  "totalElements": 100,
  "totalPages": 5
}
```

#### 3. Perform Order Action
```http
POST /api/v2/admin/orders/{orderId}/actions
Authorization: Bearer <token>
Content-Type: application/json

{
  "action": "stop",
  "reason": "User request",
  "newQuantity": 500,
  "newStartCount": 150
}
```

**Actions:**
- `stop`: Pause order
- `resume` / `start`: Resume paused order
- `refill`: Refill order with new quantity
- `cancel`: Cancel order
- `update_start_count`: Update start count
- `complete`: Mark as completed

**Response:**
```json
{
  "data": {
    "message": "Action completed successfully"
  },
  "success": true
}
```

#### 4. Bulk Actions
```http
POST /api/v2/admin/orders/bulk-actions
Authorization: Bearer <token>
Content-Type: application/json

{
  "orderIds": [1, 2, 3, 4, 5],
  "action": "CANCEL",
  "reason": "System maintenance"
}
```

**Response:**
```json
{
  "data": {
    "message": "Bulk action completed",
    "processed": 5
  },
  "success": true
}
```

#### 5. Get Conversion Coefficients
```http
GET /api/v2/admin/conversion-coefficients
Authorization: Bearer <token>
```

**Response:**
```json
[
  {
    "serviceId": 1,
    "serviceName": "YouTube Views",
    "coefficient": "1.2",
    "lastUpdated": "2024-01-01T12:00:00Z"
  }
]
```

#### 6. Update Conversion Coefficient (Admin Only)
```http
PUT /api/v2/admin/conversion-coefficients/{serviceId}
Authorization: Bearer <token>
Content-Type: application/json

{
  "coefficient": "1.5",
  "reason": "Market adjustment"
}
```

**Response:**
```json
{
  "serviceId": 1,
  "serviceName": "YouTube Views",
  "coefficient": "1.5",
  "lastUpdated": "2024-01-01T12:00:00Z"
}
```

#### 7. Get YouTube Accounts
```http
GET /api/v2/admin/youtube-accounts?page=0&size=20
Authorization: Bearer <token>
```

**Response:**
```json
{
  "content": [
    {
      "id": 1,
      "email": "account1@gmail.com",
      "status": "ACTIVE",
      "dailyLimit": 100,
      "dailyUsed": 25,
      "totalProcessed": 1000,
      "lastUsed": "2024-01-01T12:00:00Z"
    }
  ],
  "totalElements": 10,
  "totalPages": 1
}
```

#### 8. Reset YouTube Account Daily Limit (Admin Only)
```http
POST /api/v2/admin/youtube-accounts/{id}/reset-daily-limit
Authorization: Bearer <token>
```

**Response:** `204 No Content`

#### 9. Update YouTube Account Status (Admin Only)
```http
PUT /api/v2/admin/youtube-accounts/{id}/status
Authorization: Bearer <token>
Content-Type: application/json

{
  "status": "INACTIVE"
}
```

**Response:** `204 No Content`

#### 10. Get System Health
```http
GET /api/v2/admin/system/health
Authorization: Bearer <token>
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "database": {
      "status": "UP",
      "details": {
        "activeConnections": 5,
        "maxConnections": 50
      }
    },
    "kafka": {
      "status": "UP",
      "details": {
        "consumerLag": 0,
        "topics": 5
      }
    },
    "redis": {
      "status": "UP",
      "details": {
        "usedMemory": "128MB",
        "hitRate": "95%"
      }
    }
  }
}
```

#### 11. Get Operator Logs
```http
GET /api/v2/admin/logs/operator?operatorUsername=admin&action=ORDER_CANCEL&dateFrom=2024-01-01&dateTo=2024-01-31&page=0&size=20
Authorization: Bearer <token>
```

**Response:**
```json
{
  "content": [
    {
      "id": 1,
      "operatorUsername": "admin",
      "action": "ORDER_CANCEL",
      "targetId": 12345,
      "targetType": "ORDER",
      "details": "Cancelled order due to user request",
      "ipAddress": "192.168.1.1",
      "timestamp": "2024-01-01T12:00:00Z"
    }
  ],
  "totalElements": 50,
  "totalPages": 3
}
```

---

### API Keys API

#### 1. Generate API Key
```http
POST /api/v1/api-keys/generate
Authorization: Bearer <token>
```

**Response:**
```json
{
  "apiKey": "sk_live_xxxxxxxxxxxxxxxxxxx",
  "createdAt": "2024-01-01T12:00:00Z",
  "message": "API key generated successfully. Store it securely as it won't be shown again."
}
```

#### 2. Rotate API Key
```http
POST /api/v1/api-keys/rotate
Authorization: Bearer <token>
```

**Response:**
```json
{
  "apiKey": "sk_live_yyyyyyyyyyyyyyyyyyyy",
  "rotatedAt": "2024-01-01T12:00:00Z",
  "message": "API key rotated successfully. The old key is now invalid."
}
```

#### 3. Revoke API Key
```http
DELETE /api/v1/api-keys/revoke
Authorization: Bearer <token>
```

**Response:**
```json
{
  "success": true,
  "message": "API key revoked successfully"
}
```

#### 4. Get API Key Info
```http
GET /api/v1/api-keys/info
Authorization: Bearer <token>
```

**Response:**
```json
{
  "hasApiKey": true,
  "lastRotated": "2024-01-01T12:00:00Z",
  "createdAt": "2023-12-01T12:00:00Z"
}
```

---

### Perfect Panel Compatibility API

These endpoints maintain 100% compatibility with Perfect Panel API format.

#### 1. Universal Endpoint
```http
POST /api/v2?key={api_key}&action={action}&service={service_id}&link={url}&quantity={amount}&order={order_id}
```

**Actions:**
- `add`: Create new order
- `status`: Check order status
- `services`: List all services
- `balance`: Check user balance

#### 2. Add Order
```http
POST /api/v2?key=your_api_key&action=add&service=1&link=https://youtube.com/watch?v=xxx&quantity=1000
```

**Response:**
```json
{
  "order": 12345,
  "status": "Success"
}
```

#### 3. Check Order Status
```http
POST /api/v2?key=your_api_key&action=status&order=12345
```

**Response:**
```json
{
  "charge": "10.00",
  "start_count": 100,
  "status": "In progress",
  "remains": 900,
  "currency": "USDT"
}
```

#### 4. Get Services List
```http
POST /api/v2?key=your_api_key&action=services
```

**Response:**
```json
[
  {
    "service": 1,
    "name": "YouTube Views",
    "category": "YouTube",
    "rate": "10.00",
    "min": 100,
    "max": 10000,
    "type": "Default"
  }
]
```

#### 5. Check Balance
```http
POST /api/v2?key=your_api_key&action=balance
```

**Response:**
```json
{
  "balance": "100.00",
  "currency": "USDT"
}
```

---

### Webhooks API

#### 1. Cryptomus Payment Webhook
```http
POST /api/v2/webhooks/cryptomus
X-Signature: webhook_signature
Content-Type: application/json

{
  "order_id": "12345",
  "payment_id": "pay_xxxxx",
  "status": "paid",
  "amount": "50.00",
  "currency": "USDT",
  "network": "TRC20",
  "address": "TXxxxxxxxxxxx",
  "txid": "transaction_hash",
  "is_final": true,
  "updated_at": 1704067200
}
```

**Response:**
```json
{
  "status": "success"
}
```

#### 2. Test Webhook
```http
POST /api/v2/webhooks/test
Content-Type: application/json

{
  "test": "data",
  "timestamp": 1704067200
}
```

**Response:**
```json
{
  "status": "received",
  "timestamp": "1704067200000"
}
```

#### 3. Webhook Health Check
```http
GET /api/v2/webhooks/health
```

**Response:**
```json
{
  "status": "healthy",
  "service": "webhooks"
}
```

---

### Monitoring APIs

#### 1. Kafka Consumer Groups
```http
GET /api/v1/kafka/consumer-groups
Authorization: Bearer <token>
```

**Response:**
```json
{
  "groups": [
    {
      "groupId": "smm-panel-group",
      "state": "STABLE",
      "members": 3,
      "lag": 0
    }
  ]
}
```

#### 2. Kafka Error Monitoring
```http
GET /api/v1/kafka/errors?days=7
Authorization: Bearer <token>
```

**Response:**
```json
{
  "totalErrors": 5,
  "errorsByType": {
    "DESERIALIZATION": 2,
    "PROCESSING": 3
  },
  "recentErrors": [
    {
      "timestamp": "2024-01-01T12:00:00Z",
      "topic": "order-events",
      "error": "Processing failed",
      "message": "Error details"
    }
  ]
}
```

#### 3. Hibernate Statistics
```http
GET /api/v1/monitoring/hibernate/stats
Authorization: Bearer <token>
```

**Response:**
```json
{
  "secondLevelCacheHitCount": 1000,
  "secondLevelCacheMissCount": 50,
  "queryExecutionCount": 5000,
  "entityLoadCount": 10000,
  "sessionOpenCount": 500,
  "sessionCloseCount": 495
}
```

#### 4. Error Recovery Status
```http
GET /api/v1/error-recovery/status
Authorization: Bearer <token>
```

**Response:**
```json
{
  "dlqSize": 10,
  "processingErrors": 5,
  "recoveredToday": 20,
  "failedToday": 2
}
```

#### 5. Offer Assignment Status
```http
GET /api/v1/offers/assignment/status
Authorization: Bearer <token>
```

**Response:**
```json
{
  "pendingAssignments": 5,
  "activeAssignments": 25,
  "completedToday": 100,
  "failedToday": 2
}
```

---

## WebSocket Support

The API supports WebSocket connections for real-time updates.

### Connection Endpoint
```
ws://localhost:8080/ws
```

### Authentication
Include JWT token as query parameter:
```
ws://localhost:8080/ws?token=<jwt_token>
```

### Message Types

#### Order Update
```json
{
  "type": "ORDER_UPDATE",
  "orderId": 12345,
  "status": "PROCESSING",
  "progress": 250,
  "remains": 750,
  "timestamp": "2024-01-01T12:00:00Z"
}
```

#### System Notification
```json
{
  "type": "SYSTEM_NOTIFICATION",
  "severity": "INFO",
  "message": "System maintenance scheduled",
  "timestamp": "2024-01-01T12:00:00Z"
}
```

---

## Response Formats

### Standard Success Response
```json
{
  "data": {},
  "success": true,
  "message": "Operation successful",
  "timestamp": "2024-01-01T12:00:00Z"
}
```

### Paginated Response
```json
{
  "content": [],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false,
  "numberOfElements": 20
}
```

### Perfect Panel Response Format
```json
{
  "data": {},
  "success": true,
  "message": "string",
  "error": null,
  "code": 200
}
```

---

## Testing

### Development Environment
Use the Swagger UI for interactive API testing:
```
http://localhost:8080/swagger-ui.html
```

### cURL Examples

#### Authentication
```bash
# Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'

# Use JWT token
curl -X GET http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <jwt_token>"
```

#### Create Order
```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <jwt_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "service": 1,
    "link": "https://youtube.com/watch?v=xxx",
    "quantity": 1000
  }'
```

#### Perfect Panel API
```bash
# Add order using API key
curl -X POST "http://localhost:8080/api/v2?key=your_api_key&action=add&service=1&link=https://youtube.com/watch?v=xxx&quantity=1000"

# Check order status
curl -X POST "http://localhost:8080/api/v2?key=your_api_key&action=status&order=12345"
```

---

## Security Considerations

1. **API Keys**: Store securely, never expose in client-side code
2. **JWT Tokens**: Implement proper token refresh strategy
3. **Rate Limiting**: Respect rate limits to avoid service disruption
4. **HTTPS**: Always use HTTPS in production
5. **Input Validation**: All inputs are validated server-side
6. **SQL Injection**: Protected via parameterized queries
7. **XSS Protection**: Content-Type headers enforced
8. **CSRF Protection**: Enabled for web endpoints

---

## Support & Contact

For API support, integration assistance, or to report issues:
- **GitHub Issues**: [Project Repository](https://github.com/your-org/smm-panel)
- **Email**: api-support@your-domain.com
- **Documentation**: This document
- **OpenAPI Spec**: Available at `/v3/api-docs`

---

## Changelog

### Version 1.0.0 (Current)
- Initial API release
- JWT and API key authentication
- Complete order management system
- Perfect Panel compatibility
- Admin dashboard APIs
- WebSocket support for real-time updates
- Comprehensive monitoring endpoints

---

## License

This API is proprietary software. Unauthorized use is prohibited.

Copyright © 2024 SMM Panel. All rights reserved.