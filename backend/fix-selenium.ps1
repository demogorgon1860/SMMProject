# PowerShell script to fix Selenium/ChromeDriver issues

Write-Host "=== SMM Panel Selenium Fix Script ===" -ForegroundColor Green
Write-Host ""

# 1. Check if Docker is running
Write-Host "1. Checking Docker status..." -ForegroundColor Yellow
$dockerStatus = docker version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "   ERROR: Docker is not running. Please start Docker Desktop." -ForegroundColor Red
    exit 1
} else {
    Write-Host "   Docker is running." -ForegroundColor Green
}

# 2. Check Selenium containers
Write-Host ""
Write-Host "2. Checking Selenium containers..." -ForegroundColor Yellow
$seleniumHub = docker ps --filter "name=smm_selenium_hub" --format "table {{.Names}}\t{{.Status}}"
$seleniumChrome = docker ps --filter "name=smm_selenium_chrome" --format "table {{.Names}}\t{{.Status}}"

if ($seleniumHub -notmatch "smm_selenium_hub") {
    Write-Host "   Selenium Hub is NOT running." -ForegroundColor Red
    Write-Host "   Starting Selenium containers..." -ForegroundColor Yellow
    docker-compose up -d selenium-hub selenium-chrome
    Start-Sleep -Seconds 10
} else {
    Write-Host "   Selenium Hub is running." -ForegroundColor Green
}

if ($seleniumChrome -notmatch "smm_selenium_chrome") {
    Write-Host "   Selenium Chrome is NOT running." -ForegroundColor Red
    Write-Host "   Starting Chrome node..." -ForegroundColor Yellow
    docker-compose up -d selenium-chrome
    Start-Sleep -Seconds 5
} else {
    Write-Host "   Selenium Chrome is running." -ForegroundColor Green
}

# 3. Test Selenium Grid connectivity
Write-Host ""
Write-Host "3. Testing Selenium Grid connectivity..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:4444/wd/hub/status" -UseBasicParsing
    if ($response.StatusCode -eq 200) {
        Write-Host "   Selenium Grid is accessible at http://localhost:4444" -ForegroundColor Green
        $status = $response.Content | ConvertFrom-Json
        Write-Host "   Ready: $($status.value.ready)" -ForegroundColor Cyan
    }
} catch {
    Write-Host "   ERROR: Cannot connect to Selenium Grid at http://localhost:4444" -ForegroundColor Red
    Write-Host "   Restarting Selenium containers..." -ForegroundColor Yellow
    docker-compose restart selenium-hub selenium-chrome
    Start-Sleep -Seconds 15
}

# 4. Check Chrome version (local)
Write-Host ""
Write-Host "4. Checking Chrome installation..." -ForegroundColor Yellow
$chromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$chromePath86 = "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"

if (Test-Path $chromePath) {
    $chromeVersion = (Get-Item $chromePath).VersionInfo.FileVersion
    Write-Host "   Chrome found: Version $chromeVersion" -ForegroundColor Green
} elseif (Test-Path $chromePath86) {
    $chromeVersion = (Get-Item $chromePath86).VersionInfo.FileVersion
    Write-Host "   Chrome found (x86): Version $chromeVersion" -ForegroundColor Green
} else {
    Write-Host "   Chrome not found in default location." -ForegroundColor Yellow
    Write-Host "   Selenium Grid will use its own Chrome instance." -ForegroundColor Cyan
}

# 5. Download ChromeDriver (optional for local testing)
Write-Host ""
Write-Host "5. ChromeDriver setup..." -ForegroundColor Yellow
$chromeDriverPath = ".\chromedriver.exe"

if (Test-Path $chromeDriverPath) {
    Write-Host "   ChromeDriver found in current directory." -ForegroundColor Green
} else {
    Write-Host "   ChromeDriver not found locally." -ForegroundColor Yellow
    Write-Host "   For local testing without Docker, download from:" -ForegroundColor Cyan
    Write-Host "   https://chromedriver.chromium.org/downloads" -ForegroundColor Cyan
    Write-Host "   Note: Match version with your Chrome browser." -ForegroundColor Yellow
}

# 6. Update application.yml for local testing
Write-Host ""
Write-Host "6. Configuration recommendations..." -ForegroundColor Yellow
Write-Host "   For Selenium Grid (Docker): " -ForegroundColor Cyan
Write-Host "     SELENIUM_HUB_URL=http://localhost:4444/wd/hub"
Write-Host "   For Local ChromeDriver: " -ForegroundColor Cyan
Write-Host "     SELENIUM_HUB_URL=http://localhost:4444/wd/hub"
Write-Host "     TEST_MODE=true"

# 7. Test the setup
Write-Host ""
Write-Host "7. Testing Selenium with Spring Boot..." -ForegroundColor Yellow
Write-Host "   Please ensure your Spring Boot app is running." -ForegroundColor Cyan
Write-Host "   Then test at: http://localhost:8080/api/admin/selenium/test-connection" -ForegroundColor Cyan

# 8. View Selenium Grid Console
Write-Host ""
Write-Host "=== Selenium Grid Console ===" -ForegroundColor Green
Write-Host "   View Grid status: http://localhost:4444/ui" -ForegroundColor Cyan
Write-Host "   View sessions: http://localhost:4444/ui/index.html#/sessions" -ForegroundColor Cyan

# 9. Troubleshooting tips
Write-Host ""
Write-Host "=== Troubleshooting Tips ===" -ForegroundColor Yellow
Write-Host "1. If stuck at 30%, check Docker logs:" -ForegroundColor Cyan
Write-Host "   docker logs smm_selenium_chrome"
Write-Host "2. Restart containers if needed:" -ForegroundColor Cyan
Write-Host "   docker-compose restart selenium-hub selenium-chrome"
Write-Host "3. For memory issues, increase Docker memory to 4GB+" -ForegroundColor Cyan
Write-Host "4. Check firewall isn't blocking port 4444" -ForegroundColor Cyan

Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Green