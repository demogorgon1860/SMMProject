@echo off
REM Liquibase Database Sync Script for Windows
REM This script safely syncs an existing database with Liquibase
REM It marks all existing schema as already applied without recreating tables

echo ==========================================
echo Liquibase Database Sync Script
echo ==========================================

REM Configuration
set DB_HOST=localhost
set DB_PORT=5432
set DB_NAME=smm_panel
set DB_USER=smm_admin
set DB_PASSWORD=postgres123
set JDBC_URL=jdbc:postgresql://%DB_HOST%:%DB_PORT%/%DB_NAME%

echo Database: %DB_NAME%
echo User: %DB_USER%
echo.

REM Step 1: Check PostgreSQL connection
echo Step 1: Testing database connection...
psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d %DB_NAME% -c "SELECT version();" > nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Cannot connect to database. Please check your credentials.
    exit /b 1
)
echo Database connection successful!
echo.

REM Step 2: Run Liquibase changelog-sync to mark all changeSets as executed
echo Step 2: Syncing Liquibase with existing database schema...
echo This will mark all changeSets as already executed without modifying the schema.
echo.

REM Use Gradle to run Liquibase changelog-sync
call gradlew.bat liquibaseChangelogSync ^
    -Pliquibase.url="%JDBC_URL%" ^
    -Pliquibase.username="%DB_USER%" ^
    -Pliquibase.password="%DB_PASSWORD%" ^
    -Pliquibase.changeLogFile="src/main/resources/db/changelog/db.changelog-master.xml" ^
    -Pliquibase.contexts="dev"

if %ERRORLEVEL% EQU 0 (
    echo Liquibase sync completed successfully!
) else (
    echo ERROR: Liquibase sync failed!
    exit /b 1
)

REM Step 3: Display current status
echo.
echo Step 3: Current Liquibase status:
call gradlew.bat liquibaseStatus ^
    -Pliquibase.url="%JDBC_URL%" ^
    -Pliquibase.username="%DB_USER%" ^
    -Pliquibase.password="%DB_PASSWORD%" ^
    -Pliquibase.changeLogFile="src/main/resources/db/changelog/db.changelog-master.xml" ^
    -Pliquibase.contexts="dev"

echo.
echo ==========================================
echo Liquibase sync completed successfully!
echo ==========================================
echo.
echo Next steps:
echo 1. Review the databasechangelog table to confirm all changeSets are marked as executed
echo 2. Enable Liquibase in your application.yml or .env file
echo 3. Future schema changes should be added as new changeSets
echo.
pause