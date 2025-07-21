# SMM Panel Backend API

[![Java Version](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A high-performance SMM Panel Backend API built with Spring Boot 3.2, providing social media marketing services through a RESTful API.

## Features

- 🔐 **JWT Authentication & Authorization**
- 📊 **Rate Limiting** with Bucket4j and Redis
- 📝 **API Documentation** with Swagger UI
- 📈 **Monitoring** with Spring Boot Actuator and Prometheus
- 🚀 **High Performance** with Caching and Async Processing
- 🔄 **Database Migrations** with Liquibase
- 📦 **Docker** and **Docker Compose** support
- 📱 **RESTful API** with HATEOAS

## Tech Stack

- **Java 17**
- **Spring Boot 3.2**
- **Spring Security**
- **Spring Data JPA**
- **PostgreSQL**
- **Redis**
- **Kafka**
- **Liquibase**
- **Swagger/OpenAPI 3.0**
- **Docker**
- **JUnit 5** & **Mockito**

## Prerequisites

- Java 17 or later
- Maven 3.6+ or Gradle 7.6+
- Docker and Docker Compose
- PostgreSQL 14+
- Redis 7+
- Kafka 3.0+

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/smm-panel-backend.git
cd smm-panel-backend
```

### 2. Configure Environment Variables

Create a `.env` file in the root directory:

```env
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=smm_panel
DB_USER=postgres
DB_PASSWORD=yourpassword

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# JWT
JWT_SECRET=your-jwt-secret
JWT_EXPIRATION_MS=86400000

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

### 3. Run with Docker Compose

The easiest way to run the application is using Docker Compose:

```bash
docker-compose up -d
```

This will start:
- PostgreSQL database
- Redis cache
- Kafka with Zookeeper
- SMM Panel Backend API

### 4. Access the Application

- **API Documentation**: http://localhost:8080/api/swagger-ui.html
- **Actuator Health**: http://localhost:8080/api/actuator/health
- **Prometheus Metrics**: http://localhost:8080/api/actuator/prometheus

## API Endpoints

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST   | /api/v1/auth/login | User login |
| POST   | /api/v1/auth/register | User registration |
| POST   | /api/v1/auth/refresh-token | Refresh JWT token |

### Orders

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | /api/v1/orders | Get all orders |
| POST   | /api/v1/orders | Create new order |
| GET    | /api/v1/orders/{id} | Get order by ID |
| PUT    | /api/v1/orders/{id} | Update order |
| DELETE | /api/v1/orders/{id} | Delete order |

## Development

### Build the Application

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Code Style

This project uses [Spotless](https://github.com/diffplug/spotless) for code formatting:

```bash
./gradlew spotlessApply
```

## Deployment

### Build Docker Image

```bash
docker build -t smm-panel-backend .
```

### Run Docker Container

```bash
docker run -d -p 8080:8080 --name smm-panel-backend smm-panel-backend
```

## Monitoring

The application exposes several monitoring endpoints via Spring Boot Actuator:

- `/actuator/health` - Application health
- `/actuator/info` - Application information
- `/actuator/metrics` - Application metrics
- `/actuator/prometheus` - Prometheus metrics

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

For support, please open an issue or contact us at support@smmpanel.com