#!/bin/bash

set -e

echo "Building SMM Panel application..."

# Build backend
echo "Building backend..."
cd backend
mvn clean package -DskipTests
cd ..

# Build Docker image
echo "Building Docker image..."
docker build -t smm-panel:latest .

echo "Build completed successfully!"

# Optional: Run tests
if [ "$1" = "--with-tests" ]; then
    echo "Running tests..."
    cd backend
    mvn test
    cd ..
fi

echo "SMM Panel application built successfully!"
