#!/bin/bash
set -e

echo "🚀 SMM Panel Quick Start"
echo "========================"

# Check prerequisites
echo "🔍 Checking prerequisites..."
command -v docker >/dev/null 2>&1 || { echo "❌ Docker is required but not installed. Aborting." >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || { echo "❌ Docker Compose is required but not installed. Aborting." >&2; exit 1; }

# Create .env from example if not exists
if [ ! -f .env ]; then
    echo "📝 Creating .env file..."
    cp .env.example .env
    echo "⚠️  Please edit .env file with your actual credentials!"
    read -p "Press enter to continue after editing .env file..."
fi

# Create necessary directories
echo "📁 Creating directories..."
mkdir -p backend/{src/main/{java,resources},logs}
mkdir -p frontend/{src,public,build}
mkdir -p data/{postgres,redis,kafka,elasticsearch}
mkdir -p logs
mkdir -p backups

# Set execute permissions
chmod +x quickstart.sh

echo "🚀 Starting SMM Panel infrastructure..."

# Start infrastructure services first
echo "🏗️ Starting infrastructure services..."
docker-compose up -d postgres redis kafka zookeeper

# Wait for services to be ready
echo "⏳ Waiting for services to be ready..."
sleep 30

# Check if database is ready
echo "🗄️ Waiting for database..."
until docker exec smm_postgres pg_isready -U ${POSTGRES_USER:-smm_admin} -d smm_panel; do
  echo "Database is unavailable - sleeping"
  sleep 1
done
echo "Database is up!"

# Build and start application services
echo "🔨 Building and starting application..."
docker-compose up -d --build

# Check health
echo "🏥 Checking service health..."
sleep 20

# Function to check service health
check_service() {
    local service=$1
    local url=$2
    local max_attempts=30
    local attempt=1

    echo -n "Checking $service... "
    while [ $attempt -le $max_attempts ]; do
        if curl -s "$url" > /dev/null 2>&1; then
            echo "✓ Healthy"
            return 0
        fi
        sleep 2
        attempt=$((attempt + 1))
    done
    echo "✗ Unhealthy after $max_attempts attempts"
    return 1
}

# Check services
check_service "Backend API" "http://localhost:8080/actuator/health"
check_service "Frontend" "http://localhost:3001"

echo ""
echo "✅ Deployment complete!"
echo ""
echo "📊 Access points:"
echo "  - Frontend: http://localhost:3001"
echo "  - API: http://localhost:8080/api/v2"
echo "  - API Health: http://localhost:8080/actuator/health"
echo "  - Selenium Hub: http://localhost:4444"
echo ""
echo "📝 Default test credentials:"
echo "  Create a new account through the frontend interface"
echo ""
echo "🎉 Your SMM Panel is ready!"
echo ""
echo "📚 Next steps:"
echo "  1. Create an admin user through the frontend"
echo "  2. Configure YouTube accounts in the admin panel"
echo "  3. Set up traffic sources"
echo "  4. Configure Binom and Cryptomus API keys"
echo "  5. Start processing orders!"
echo ""
echo "📖 For more information, check the documentation in README.md"