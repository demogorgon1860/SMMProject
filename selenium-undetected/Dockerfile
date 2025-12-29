# ==============================================================================
# Undetected Chrome Selenium Node - Masks All Automation Traces
# ==============================================================================
FROM selenium/node-chrome:4.16.1-20231219

USER root

# Install Python 3 and pip
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-dev \
    wget \
    unzip && \
    rm -rf /var/lib/apt/lists/*

# Install undetected-chromedriver and dependencies
RUN pip3 install --no-cache-dir \
    undetected-chromedriver==3.5.4 \
    selenium==4.16.0 \
    requests

# Patch ChromeDriver binary to remove detection signatures
RUN chromedriver_path="/opt/selenium/chromedriver" && \
    if [ -f "$chromedriver_path" ]; then \
        cp "$chromedriver_path" "${chromedriver_path}.original" && \
        sed -i 's/cdc_/xyz_/g' "$chromedriver_path" && \
        echo "ChromeDriver patched successfully"; \
    else \
        echo "WARNING: ChromeDriver not found at $chromedriver_path"; \
    fi

# Add Chrome stealth arguments via environment variable
# These arguments mask automation and bypass detection
ENV SE_OPTS="--log-level INFO" \
    SE_NODE_GRID_URL="http://selenium-hub:4444" \
    CHROME_EXTRA_ARGS="--disable-blink-features=AutomationControlled --disable-dev-shm-usage --disable-infobars --disable-browser-side-navigation --disable-gpu --no-sandbox --disable-setuid-sandbox --disable-web-security --disable-features=IsolateOrigins,site-per-process --allow-running-insecure-content --disable-notifications --disable-popup-blocking --start-maximized --disable-extensions --aggressive-cache-discard --disable-cache --disable-application-cache --disable-offline-load-stale-cache --disk-cache-size=0 --disable-background-networking --disable-default-apps --disable-sync --metrics-recording-only --mute-audio --no-first-run --user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

# Override Chrome preferences to disable WebDriver detection
RUN mkdir -p /tmp/chrome-prefs && \
    echo '{"profile":{"default_content_setting_values":{"notifications":2}},"credentials_enable_service":false,"profile.password_manager_enabled":false}' > /tmp/chrome-prefs/preferences.json

# Inject stealth JavaScript to mask navigator.webdriver
RUN mkdir -p /opt/selenium/stealth && \
    echo 'Object.defineProperty(navigator, "webdriver", {get: () => undefined});' > /opt/selenium/stealth/stealth.js && \
    echo 'window.navigator.chrome = {runtime: {}};' >> /opt/selenium/stealth/stealth.js && \
    echo 'Object.defineProperty(navigator, "plugins", {get: () => [1, 2, 3, 4, 5]});' >> /opt/selenium/stealth/stealth.js && \
    echo 'Object.defineProperty(navigator, "languages", {get: () => ["en-US", "en"]});' >> /opt/selenium/stealth/stealth.js

# Set environment variables to disable automation indicators
ENV SE_ENABLE_TRACING=false \
    SE_ENABLE_MANAGED_DOWNLOADS=false \
    SE_DOWNLOAD_FOLDER=/tmp/downloads \
    SE_NODE_OVERRIDE_MAX_SESSIONS=true \
    SE_SCREEN_WIDTH=1920 \
    SE_SCREEN_HEIGHT=1080

USER seluser

# Default Selenium Grid entry point (uses our patched ChromeDriver and env vars)
CMD ["/opt/bin/entry_point.sh"]
