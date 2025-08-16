# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Social Media Marketing (SMM) Panel application consisting of a Spring Boot backend API and React frontend. The system provides social media marketing services through a RESTful API with comprehensive monitoring, caching, and integration capabilities.

## Architecture

The application follows a monolith-inspired architecture with these key layers:

- **Frontend**: React + TypeScript SPA using Vite, Tailwind CSS, and Zustand for state management
- **Backend**: Spring Boot 3.1.7 application with JPA, Redis caching, Kafka messaging, and JWT authentication
- **Database**: PostgreSQL with Liquibase migrations
- **Message Queue**: Kafka for asynchronous processing
- **Cache**: Redis for performance optimization
- **Monitoring**: Prometheus, Grafana, Loki, and Jaeger for observability
- **Automation**: Selenium Grid for YouTube automation
- **Container Orchestration**: Docker Compose for local development and production

## Development Commands

### Backend Development (Gradle-based)

```bash
# Build the application
cd backend && ./gradlew build

# Run tests
cd backend && ./gradlew test

# Run a specific test class
cd backend && ./gradlew test --tests "OrderProcessingServiceTest"

# Run specific test method
cd backend && ./gradlew test --tests "OrderProcessingServiceTest.testOrderCreation"

# Clean build
cd backend && ./gradlew clean build

# Run application in development mode
cd backend && ./gradlew bootRun

# Generate test coverage report
cd backend && ./gradlew test jacocoTestReport

# Apply code formatting (Spotless)
cd backend && ./gradlew spotlessApply

# Database migration validation
cd backend && ./gradlew flywayValidate

# Start application with profile
cd backend && SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

### Frontend Development

```bash
# Install dependencies
cd frontend && npm install

# Start development server
cd frontend && npm run dev

# Build for production
cd frontend && npm run build

# Run tests
cd frontend && npm test

# Run tests with UI
cd frontend && npm run test:ui

# Generate test coverage
cd frontend && npm run test:coverage

# Run end-to-end tests
cd frontend && npm run e2e

# Lint code
cd frontend && npm run lint

# Fix linting issues
cd frontend && npm run lint:fix

# Type checking
cd frontend && npm run type-check
```

### Docker Operations

```bash
# Start full development environment
docker-compose -f docker-compose.dev.yml up -d

# Start production environment
docker-compose up -d

# View logs for specific service
docker-compose logs -f spring-boot-app

# Rebuild and restart specific service
docker-compose up -d --build spring-boot-app

# Stop all services
docker-compose down

# Clean up volumes (WARNING: destroys data)
docker-compose down -v

# Scale backend instances
docker-compose up -d --scale spring-boot-app=3
```

### Database Operations

```bash
# Access PostgreSQL CLI
docker-compose exec postgres psql -U smm_admin -d smm_panel

# Run database migrations
cd backend && ./gradlew flywayMigrate

# Create new migration file
# Place in: backend/src/main/resources/db/migration/V{version}__{description}.sql

# Backup database
docker-compose exec postgres pg_dump -U smm_admin smm_panel > backup.sql

# Restore database
docker-compose exec -T postgres psql -U smm_admin smm_panel < backup.sql
```

## Key Configuration Files

- **Backend Config**: `backend/src/main/resources/application.yml` - Main Spring Boot configuration
- **Frontend Config**: `frontend/vite.config.ts` - Vite build configuration
- **Docker**: `docker-compose.yml` (production) and `docker-compose.dev.yml` (development)
- **Database**: `init-scripts/` contains SQL initialization scripts
- **Monitoring**: `prometheus/prometheus.yml`, `grafana/` dashboard configs

## Critical Development Notes

### Database Management
- Uses Liquibase for database migrations (not Flyway despite gradle config)
- Migration files are in `backend/src/main/resources/db/migration/`
- Always validate migrations before applying: `./gradlew flywayValidate`
- Fixed campaign data is managed through init scripts in `init-scripts/`

### API Structure
- REST API base path: `/api`
- API versioning: `/api/v1/` for current version
- Perfect Panel API compatibility maintained in `/api/v1/` endpoints
- Authentication: JWT tokens or API keys via `X-API-Key` header
- Rate limiting enabled (configurable per endpoint type)

### Message Processing
- Kafka topics handle asynchronous processing for orders, video processing, and offer assignments
- Event sourcing pattern used for order state changes
- Consumer groups: `smm-panel-group`
- Key message types: order events, video processing events, offer assignment events

### Testing Strategy
- Unit tests: Focus on service layer business logic
- Integration tests: Test with TestContainers (PostgreSQL, Kafka, Redis)
- End-to-end tests: Full API workflow testing
- Frontend tests: Vitest + Testing Library for component and integration tests

### Security Configuration
- JWT authentication with refresh tokens
- API key authentication for external integrations
- CORS configured for development and production origins
- Rate limiting via Redis-backed Bucket4j implementation
- Spring Security handles authorization with role-based access

### External Integrations
- **Binom**: Campaign management and tracking integration
- **YouTube API**: Video stats and account management
- **Cryptomus**: Payment processing integration
- **Selenium Grid**: Automated YouTube interactions

### Performance Optimization
- Redis caching for services, users, conversion coefficients
- Hibernate second-level cache enabled
- Connection pooling with HikariCP (50 max connections)
- Async processing with configurable thread pools
- Circuit breakers via Resilience4j for external API calls

### Monitoring and Observability
- Spring Boot Actuator endpoints: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`
- Prometheus metrics collection enabled
- Grafana dashboards for visualization
- Loki for log aggregation
- Jaeger for distributed tracing
- Custom health indicators for Kafka and offer assignment services

## Common Development Workflows

### Adding a New REST Endpoint
1. Create DTO classes in appropriate `dto/` package
2. Add controller method in relevant controller class
3. Implement service layer logic
4. Add repository methods if needed
5. Write unit and integration tests
6. Update API documentation if using OpenAPI annotations

### Adding New Database Entity
1. Create entity class in `entity/` package with JPA annotations
2. Create repository interface extending appropriate Spring Data interface
3. Create Liquibase migration file
4. Add service layer methods
5. Create DTOs for API exposure
6. Write comprehensive tests

### Adding Kafka Message Processing
1. Define event/message classes in `event/` or `dto/` packages
2. Add producer methods in appropriate producer class
3. Create consumer methods with `@KafkaListener`
4. Configure topic mapping in `application.yml`
5. Add integration tests with embedded Kafka

### Frontend Component Development
1. Create component in appropriate directory under `frontend/src/components/`
2. Add TypeScript interfaces in `frontend/src/types/`
3. Implement API service calls in `frontend/src/services/api.ts`
4. Add state management if needed (Zustand store)
5. Write component tests with Testing Library
6. Add to appropriate page/layout

## Environment Setup Notes

- Java 17+ required for backend development
- Node.js 18+ required for frontend development
- Docker and Docker Compose required for full development environment
- Redis, PostgreSQL, and Kafka dependencies managed via Docker Compose
- Selenium requires Chrome WebDriver for YouTube automation features

## Troubleshooting

### Compilation Issues
- Run `cd backend && ./gradlew clean build` to resolve Lombok annotation processing issues
- Ensure `annotationProcessor` configuration is correct in `build.gradle`

### Database Connection Issues
- Verify PostgreSQL container is running: `docker-compose ps postgres`
- Check database credentials in environment variables or `application.yml`
- Ensure database initialization scripts have executed properly

### Kafka Issues
- Verify Kafka and Zookeeper containers are healthy
- Check topic creation and message serialization configuration
- Review consumer group coordination and offset management

### Redis Connection Issues
- Verify Redis container status and network connectivity
- Check Redis password configuration if authentication is enabled
- Monitor cache hit/miss rates via actuator metrics
