# =============================================================================
# SMM Panel Docker Startup Script (Windows PowerShell)
# =============================================================================
# This script manages the Docker environment for the SMM Panel application
# Usage: .\docker-start.ps1 [dev|prod|stop|restart|logs|clean|health]
# =============================================================================

param(
    [Parameter(Position=0)]
    [ValidateSet('dev', 'prod', 'stop', 'restart', 'logs', 'clean', 'health')]
    [string]$Command = 'help',

    [Parameter(Position=1)]
    [string]$Service = ''
)

# Colors for output
$colors = @{
    Red = 'Red'
    Green = 'Green'
    Yellow = 'Yellow'
    Blue = 'Cyan'
    Default = 'White'
}

function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = 'Default'
    )
    Write-Host $Message -ForegroundColor $colors[$Color]
}

# Check if .env file exists
function Test-EnvFile {
    if (-not (Test-Path ".env")) {
        Write-ColorOutput "Error: .env file not found!" "Red"
        Write-ColorOutput "Please ensure .env file exists with all required variables" "Yellow"
        exit 1
    }
    Write-ColorOutput "✓ .env file found" "Green"
}

# Validate Docker and Docker Compose
function Test-Docker {
    try {
        $null = docker --version
        Write-ColorOutput "✓ Docker is installed" "Green"
    }
    catch {
        Write-ColorOutput "Error: Docker is not installed!" "Red"
        exit 1
    }

    try {
        $null = docker-compose --version
        Write-ColorOutput "✓ Docker Compose is installed" "Green"
    }
    catch {
        try {
            $null = docker compose version
            Write-ColorOutput "✓ Docker Compose V2 is installed" "Green"
        }
        catch {
            Write-ColorOutput "Error: Docker Compose is not installed!" "Red"
            exit 1
        }
    }
}

# Build backend JAR
function Build-Backend {
    Write-ColorOutput "Building backend application..." "Blue"
    Push-Location backend

    if (Test-Path ".\gradlew.bat") {
        & .\gradlew.bat clean build -x test
        if ($LASTEXITCODE -eq 0) {
            Write-ColorOutput "✓ Backend built successfully" "Green"
        } else {
            Write-ColorOutput "Warning: Backend build failed, Docker will build it" "Yellow"
        }
    } else {
        Write-ColorOutput "Warning: gradlew.bat not found, Docker will build it" "Yellow"
    }

    Pop-Location
}

# Start development environment
function Start-DevEnvironment {
    Write-ColorOutput "Starting development environment..." "Blue"

    docker-compose up -d

    if ($LASTEXITCODE -eq 0) {
        Write-ColorOutput "✓ Development environment started" "Green"
        Write-ColorOutput "Services available at:" "Blue"
        Write-ColorOutput "  - Frontend: http://localhost:3000" "Green"
        Write-ColorOutput "  - Backend API: http://localhost:8080" "Green"
        Write-ColorOutput "  - Kafka UI: http://localhost:8081" "Green"
        Write-ColorOutput "  - Adminer (DB): http://localhost:8082" "Green"
        Write-ColorOutput "  - Grafana: http://localhost:3001" "Green"
        Write-ColorOutput "  - Prometheus: http://localhost:9090" "Green"
        Write-ColorOutput "  - Debug Port: localhost:5005" "Green"
    } else {
        Write-ColorOutput "Failed to start development environment" "Red"
        exit 1
    }
}

# Start production environment
function Start-ProdEnvironment {
    Write-ColorOutput "Starting production environment..." "Blue"

    Build-Backend

    docker-compose -f docker-compose.yml up -d

    if ($LASTEXITCODE -eq 0) {
        Write-ColorOutput "✓ Production environment started" "Green"
        Write-ColorOutput "Services available at:" "Blue"
        Write-ColorOutput "  - Application: http://localhost" "Green"
        Write-ColorOutput "  - API: http://localhost/api" "Green"
    } else {
        Write-ColorOutput "Failed to start production environment" "Red"
        exit 1
    }
}

# Stop all services
function Stop-Services {
    Write-ColorOutput "Stopping all services..." "Yellow"
    docker-compose down
    Write-ColorOutput "✓ All services stopped" "Green"
}

# Restart services
function Restart-Services {
    Write-ColorOutput "Restarting services..." "Yellow"
    docker-compose restart
    Write-ColorOutput "✓ Services restarted" "Green"
}

# Show logs
function Show-Logs {
    param([string]$ServiceName = '')

    if ([string]::IsNullOrEmpty($ServiceName)) {
        docker-compose logs -f --tail=100
    } else {
        docker-compose logs -f --tail=100 $ServiceName
    }
}

# Clean up everything
function Clear-All {
    Write-ColorOutput "WARNING: This will remove all containers, volumes, and images!" "Red"
    $confirmation = Read-Host "Are you sure? (y/N)"

    if ($confirmation -eq 'y') {
        docker-compose down -v --rmi all
        Write-ColorOutput "✓ All Docker resources cleaned" "Green"
    } else {
        Write-ColorOutput "Cleanup cancelled" "Yellow"
    }
}

# Validate configuration
function Test-Configuration {
    Write-ColorOutput "Validating Docker Compose configuration..." "Blue"

    $output = docker-compose config 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-ColorOutput "✓ Docker Compose configuration is valid" "Green"
    } else {
        Write-ColorOutput "✗ Docker Compose configuration has errors:" "Red"
        Write-Host $output
        exit 1
    }
}

# Check service health
function Test-ServiceHealth {
    Write-ColorOutput "Checking service health..." "Blue"

    Start-Sleep -Seconds 10

    $services = @("spring-boot-app", "postgres", "redis", "kafka")

    foreach ($svc in $services) {
        $containerName = "smm_$svc"
        $running = docker inspect -f '{{.State.Running}}' $containerName 2>$null

        if ($running -eq "true") {
            Write-ColorOutput "✓ $svc is running" "Green"
        } else {
            Write-ColorOutput "✗ $svc is not running" "Red"
        }
    }

    # Check backend health endpoint
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            Write-ColorOutput "✓ Backend API is healthy" "Green"
        }
    } catch {
        Write-ColorOutput "⚠ Backend API is not responding yet" "Yellow"
    }
}

# Show help
function Show-Help {
    Write-ColorOutput "=== SMM Panel Docker Manager (Windows) ===" "Blue"
    Write-Host ""
    Write-ColorOutput "Usage: .\docker-start.ps1 <command> [service]" "Yellow"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  dev     - Start development environment"
    Write-Host "  prod    - Start production environment"
    Write-Host "  stop    - Stop all services"
    Write-Host "  restart - Restart all services"
    Write-Host "  logs    - Show logs (optionally for specific service)"
    Write-Host "  clean   - Remove all containers, volumes, and images"
    Write-Host "  health  - Check service health status"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\docker-start.ps1 dev"
    Write-Host "  .\docker-start.ps1 logs spring-boot-app"
    Write-Host "  .\docker-start.ps1 health"
}

# Main execution
Write-ColorOutput "=== SMM Panel Docker Manager ===" "Blue"

# Check prerequisites
Test-Docker
Test-EnvFile
Test-Configuration

switch ($Command) {
    'dev' {
        Start-DevEnvironment
        Test-ServiceHealth
    }
    'prod' {
        Start-ProdEnvironment
        Test-ServiceHealth
    }
    'stop' {
        Stop-Services
    }
    'restart' {
        Restart-Services
    }
    'logs' {
        Show-Logs -ServiceName $Service
    }
    'clean' {
        Clear-All
    }
    'health' {
        Test-ServiceHealth
    }
    default {
        Show-Help
    }
}