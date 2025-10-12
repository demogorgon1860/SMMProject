#!/bin/bash
# Docker environment management script for SMM Panel

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored messages
print_message() {
    echo -e "${GREEN}[SMM Panel]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Check if .env file exists
check_env_file() {
    if [ ! -f .env ]; then
        print_error ".env file not found! Creating from template..."
        cat > .env << EOF
# PostgreSQL
POSTGRES_USER=smm_admin
POSTGRES_PASSWORD=changeme_prod_password
POSTGRES_DB=smm_panel

# Redis
REDIS_PASSWORD=changeme_redis_password

# Grafana
GRAFANA_USER=admin
GRAFANA_PASSWORD=changeme_grafana_password

# Docker Registry (if using private registry)
DOCKER_REGISTRY=docker.io
VERSION=latest

# Timezone
TZ=UTC
EOF
        print_warning "Please update .env file with secure passwords!"
        exit 1
    fi
}

# Start production environment
start_prod() {
    print_message "Starting production environment..."
    check_env_file
    docker-compose -f docker-compose.prod.yml up -d
    print_message "Production environment started!"
    print_message "Services:"
    echo "  - Application: http://localhost (via Nginx)"
    echo "  - Prometheus: Internal only"
    echo "  - Grafana: Internal only"
}

# Start development environment
start_dev() {
    print_message "Starting development environment..."
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml up -d
    print_message "Development environment started!"
    print_message "Services:"
    echo "  - Application: http://localhost:8080"
    echo "  - Debug Port: localhost:5005"
    echo "  - Kafka UI: http://localhost:8081"
    echo "  - pgAdmin: http://localhost:5050"
    echo "  - RedisInsight: http://localhost:8001"
    echo "  - Prometheus: http://localhost:9090"
    echo "  - Grafana: http://localhost:3000 (admin/admin)"
    echo "  - Jaeger: http://localhost:16686"
    echo "  - Mailhog: http://localhost:8025"
    echo "  - Selenium Grid: http://localhost:4444"
    echo "  - Chrome VNC: http://localhost:7900"
}

# Stop all services
stop_all() {
    print_message "Stopping all services..."
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml down
    print_message "All services stopped!"
}

# Clean up everything
cleanup() {
    print_warning "This will remove all containers, volumes, and data!"
    read -p "Are you sure? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        print_message "Cleaning up..."
        docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml down -v
        docker system prune -af --volumes
        print_message "Cleanup complete!"
    else
        print_message "Cleanup cancelled."
    fi
}

# Show status
status() {
    print_message "Container status:"
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml ps
}

# Show logs
logs() {
    SERVICE=$2
    if [ -z "$SERVICE" ]; then
        docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml logs -f --tail=100
    else
        docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml logs -f --tail=100 $SERVICE
    fi
}

# Restart a service
restart() {
    SERVICE=$2
    if [ -z "$SERVICE" ]; then
        print_error "Please specify a service to restart"
        exit 1
    fi
    print_message "Restarting $SERVICE..."
    docker-compose -f docker-compose.prod.yml -f docker-compose.dev.yml restart $SERVICE
}

# Scale a service
scale() {
    SERVICE=$2
    COUNT=$3
    if [ -z "$SERVICE" ] || [ -z "$COUNT" ]; then
        print_error "Usage: $0 scale <service> <count>"
        exit 1
    fi
    print_message "Scaling $SERVICE to $COUNT instances..."
    docker-compose -f docker-compose.prod.yml up -d --scale $SERVICE=$COUNT
}

# Main script logic
case "$1" in
    prod)
        start_prod
        ;;
    dev)
        start_dev
        ;;
    stop)
        stop_all
        ;;
    cleanup)
        cleanup
        ;;
    status)
        status
        ;;
    logs)
        logs $@
        ;;
    restart)
        restart $@
        ;;
    scale)
        scale $@
        ;;
    *)
        echo "Usage: $0 {prod|dev|stop|cleanup|status|logs|restart|scale}"
        echo ""
        echo "Commands:"
        echo "  prod     - Start production environment"
        echo "  dev      - Start development environment"
        echo "  stop     - Stop all services"
        echo "  cleanup  - Remove all containers and volumes (DESTRUCTIVE)"
        echo "  status   - Show container status"
        echo "  logs     - Show logs (optionally specify service)"
        echo "  restart  - Restart a specific service"
        echo "  scale    - Scale a service (e.g., scale chrome 5)"
        exit 1
        ;;
esac