#!/bin/bash

set -e

ENVIRONMENT=${1:-development}
VERSION=${2:-latest}

echo "Deploying SMM Panel to $ENVIRONMENT environment..."

if [ "$ENVIRONMENT" = "production" ]; then
    echo "Production deployment..."
    
    # Pull latest images
    docker-compose -f docker-compose.prod.yml pull
    
    # Deploy with zero downtime
    docker-compose -f docker-compose.prod.yml up -d --no-deps smm-panel-app
    
    # Wait for health check
    echo "Waiting for application to be healthy..."
    timeout 300 bash -c 'until curl -f http://localhost:8080/actuator/health; do sleep 5; done'
    
    echo "Production deployment completed!"
    
elif [ "$ENVIRONMENT" = "development" ]; then
    echo "Development deployment..."
    
    # Start all services
    docker-compose up -d
    
    echo "Development environment started!"
    echo "Services:"
    echo "- Application: http://localhost:8080"
    echo "- Grafana: http://localhost:3000"
    echo "- Prometheus: http://localhost:9090"
    echo "- Kafka UI: http://localhost:8081"
    
else
    echo "Unknown environment: $ENVIRONMENT"
    echo "Usage: $0 [development|production] [version]"
    exit 1
fi
