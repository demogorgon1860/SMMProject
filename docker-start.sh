#!/bin/bash
# =============================================================================
# SMM Panel Docker Startup Script
# =============================================================================
# This script manages the Docker environment for the SMM Panel application
# Usage: ./docker-start.sh [dev|prod|stop|restart|logs|clean]
# =============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_message() {
    echo -e "${2}${1}${NC}"
}

# Function to check if .env file exists
check_env_file() {
    if [ ! -f .env ]; then
        print_message "Error: .env file not found!" "$RED"
        print_message "Please ensure .env file exists with all required variables" "$YELLOW"
        exit 1
    fi
    print_message "✓ .env file found" "$GREEN"
}

# Function to validate Docker and Docker Compose
check_docker() {
    if ! command -v docker &> /dev/null; then
        print_message "Error: Docker is not installed!" "$RED"
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        print_message "Error: Docker Compose is not installed!" "$RED"
        exit 1
    fi

    print_message "✓ Docker and Docker Compose are installed" "$GREEN"
}

# Function to build backend JAR
build_backend() {
    print_message "Building backend application..." "$BLUE"
    cd backend
    if [ -f "./gradlew" ]; then
        chmod +x ./gradlew
        ./gradlew clean build -x test
        print_message "✓ Backend built successfully" "$GREEN"
    else
        print_message "Warning: gradlew not found, Docker will build it" "$YELLOW"
    fi
    cd ..
}

# Function to start development environment
start_dev() {
    print_message "Starting development environment..." "$BLUE"

    # Use docker-compose.override.yml automatically for dev
    docker-compose up -d

    print_message "✓ Development environment started" "$GREEN"
    print_message "Services available at:" "$BLUE"
    print_message "  - Frontend: http://localhost:3000" "$GREEN"
    print_message "  - Backend API: http://localhost:8080" "$GREEN"
    print_message "  - Kafka UI: http://localhost:8081" "$GREEN"
    print_message "  - Adminer (DB): http://localhost:8082" "$GREEN"
    print_message "  - Grafana: http://localhost:3001" "$GREEN"
    print_message "  - Prometheus: http://localhost:9090" "$GREEN"
    print_message "  - Debug Port: localhost:5005" "$GREEN"
}

# Function to start production environment
start_prod() {
    print_message "Starting production environment..." "$BLUE"

    # Build backend first
    build_backend

    # Start with production compose file only
    docker-compose -f docker-compose.yml up -d

    print_message "✓ Production environment started" "$GREEN"
    print_message "Services available at:" "$BLUE"
    print_message "  - Application: http://localhost" "$GREEN"
    print_message "  - API: http://localhost/api" "$GREEN"
}

# Function to stop all services
stop_services() {
    print_message "Stopping all services..." "$YELLOW"
    docker-compose down
    print_message "✓ All services stopped" "$GREEN"
}

# Function to restart services
restart_services() {
    print_message "Restarting services..." "$YELLOW"
    docker-compose restart
    print_message "✓ Services restarted" "$GREEN"
}

# Function to show logs
show_logs() {
    SERVICE=$2
    if [ -z "$SERVICE" ]; then
        docker-compose logs -f --tail=100
    else
        docker-compose logs -f --tail=100 "$SERVICE"
    fi
}

# Function to clean up everything
clean_all() {
    print_message "WARNING: This will remove all containers, volumes, and images!" "$RED"
    read -p "Are you sure? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker-compose down -v --rmi all
        print_message "✓ All Docker resources cleaned" "$GREEN"
    else
        print_message "Cleanup cancelled" "$YELLOW"
    fi
}

# Function to validate configuration
validate_config() {
    print_message "Validating Docker Compose configuration..." "$BLUE"

    if docker-compose config > /dev/null 2>&1; then
        print_message "✓ Docker Compose configuration is valid" "$GREEN"
    else
        print_message "✗ Docker Compose configuration has errors:" "$RED"
        docker-compose config
        exit 1
    fi
}

# Function to check service health
check_health() {
    print_message "Checking service health..." "$BLUE"

    # Wait a bit for services to start
    sleep 10

    # Check each service
    SERVICES=("spring-boot-app" "postgres" "redis" "kafka")

    for SERVICE in "${SERVICES[@]}"; do
        if [ "$(docker inspect -f '{{.State.Running}}' "smm_${SERVICE}" 2>/dev/null)" == "true" ]; then
            print_message "✓ $SERVICE is running" "$GREEN"
        else
            print_message "✗ $SERVICE is not running" "$RED"
        fi
    done

    # Check backend health endpoint
    if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
        print_message "✓ Backend API is healthy" "$GREEN"
    else
        print_message "⚠ Backend API is not responding yet" "$YELLOW"
    fi
}

# Main script logic
main() {
    print_message "=== SMM Panel Docker Manager ===" "$BLUE"

    # Check prerequisites
    check_docker
    check_env_file
    validate_config

    case "$1" in
        dev)
            start_dev
            check_health
            ;;
        prod)
            start_prod
            check_health
            ;;
        stop)
            stop_services
            ;;
        restart)
            restart_services
            ;;
        logs)
            show_logs "$@"
            ;;
        clean)
            clean_all
            ;;
        health)
            check_health
            ;;
        *)
            print_message "Usage: $0 {dev|prod|stop|restart|logs|clean|health} [service]" "$YELLOW"
            print_message "  dev     - Start development environment" "$NC"
            print_message "  prod    - Start production environment" "$NC"
            print_message "  stop    - Stop all services" "$NC"
            print_message "  restart - Restart all services" "$NC"
            print_message "  logs    - Show logs (optionally for specific service)" "$NC"
            print_message "  clean   - Remove all containers, volumes, and images" "$NC"
            print_message "  health  - Check service health status" "$NC"
            exit 1
            ;;
    esac
}

# Run main function
main "$@"