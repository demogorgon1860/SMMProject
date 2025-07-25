#!/bin/bash

# Unified deployment script for all environments
ENVIRONMENT=${1:-development}  # Default to development if no argument provided

# Validate environment argument
if [[ ! "$ENVIRONMENT" =~ ^(development|staging|production)$ ]]; then
    echo "Error: Invalid environment. Use 'development', 'staging', or 'production'"
    exit 1
fi

echo "Deploying to $ENVIRONMENT environment..."

# Load environment-specific configurations
if [ -f ".env.$ENVIRONMENT" ]; then
    source ".env.$ENVIRONMENT"
else
    echo "Warning: No environment-specific configuration found at .env.$ENVIRONMENT"
fi

# Build frontend
echo "Building frontend..."
cd frontend
npm run build

# Build backend
echo "Building backend..."
cd ../backend
./gradlew clean build

# Deploy using Docker Compose
echo "Deploying with Docker Compose..."
if [ "$ENVIRONMENT" = "production" ]; then
    docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d
else
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d
fi

# Run database migrations
echo "Running database migrations..."
cd ../backend
./gradlew flywayMigrate

# Health check
echo "Performing health check..."
./docker-healthcheck.sh

echo "Deployment to $ENVIRONMENT completed successfully!"
