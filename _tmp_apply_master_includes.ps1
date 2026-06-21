$ErrorActionPreference = 'Stop'
$path = 'C:\SMMPanel\backend\src\main\resources\db\changelog\db.changelog-master.xml'

# Backup first
$backup = $path + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
Copy-Item -Path $path -Destination $backup -Force
Write-Host "Backup written: $backup"

# Read raw bytes to detect BOM, then read as UTF8
$bytes = [System.IO.File]::ReadAllBytes($path)
$hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
Write-Host "Original BOM: $hasBom"

$content = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false))

# Detect line ending
$crlf = $content.Contains("`r`n")
$nl = if ($crlf) { "`r`n" } else { "`n" }
Write-Host "Line ending: $(if ($crlf) { 'CRLF' } else { 'LF' })"

# The exact marker we want to insert BEFORE
$marker = '</databaseChangeLog>'
if (-not $content.Contains($marker)) {
    throw "Marker '$marker' not found in master file"
}

# Build the block to insert (uses the file's own newline style)
$block = @(
    '',
    '    <!-- Task 03: refresh_tokens.last_used_at + users.api_key_paused_at -->',
    '    <include file="db/changelog/changes/v2026.04-task03-sessions-and-api-pause.xml"/>',
    '',
    '    <!-- Task 09: app_settings table - backs /admin/settings persistence + runtime enforcement -->',
    '    <include file="db/changelog/changes/v2026.04-app-settings.xml"/>',
    '',
    '    <!-- Task 13.2: balance_deposits(user_id, status, created_at DESC) for deposits hot path -->',
    '    <include file="db/changelog/changes/v2026.04-task13-deposits-user-status-index.xml"/>',
    '',
    '    <!-- Task 16.5: Stripe-style idempotency keys for POST /v1/orders -->',
    '    <include file="db/changelog/changes/v2026.04-add-idempotency-keys.xml"/>',
    '',
    '    <!-- Task 16.10: Persistent admin audit log -->',
    '    <include file="db/changelog/changes/v2026.04-add-admin-audit-log.xml"/>',
    ''
) -join $nl

# Already added? Idempotency safety net
if ($content.Contains('v2026.04-app-settings.xml') -and $content.Contains('v2026.04-add-admin-audit-log.xml')) {
    Write-Host "Includes appear to already be present. Aborting to avoid duplicates."
    exit 0
}

$updated = $content.Replace($marker, $block + $nl + $marker)

# Write back preserving original encoding (UTF8 without BOM unless original had it)
$encoding = [System.Text.UTF8Encoding]::new($hasBom)
[System.IO.File]::WriteAllText($path, $updated, $encoding)

Write-Host "Includes inserted. Verifying..."
$verify = Select-String -Path $path -Pattern 'v2026.04-(task03|app-settings|task13|idempotency|admin-audit-log)' | Select-Object -ExpandProperty Line
Write-Host ($verify -join "`n")
