# 🚀 DATABASE FIX IMPLEMENTATION GUIDE
**Safe Step-by-Step Process for Production**


# 3. Apply Liquibase migrations
cd /app/backend
./gradlew Update

# 4. Run validation
docker-compose exec postgres psql -U smm_admin -d smm_panel_staging -c "SELECT * FROM check_database_health();"


### Step 4: Manual SQL Fixes if Needed (5 minutes)
```bash
# If Liquibase fails, run manual fixes
docker-compose exec -T postgres psql -U smm_admin -d smm_panel < database_fixes_manual.sql

# Verify critical fixes
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT typname FROM pg_type WHERE typname IN ('audit_category', 'audit_severity', 'video_processing_status');
"
```

### Step 5: Validate Database Health (5 minutes)
```bash
# Run health check
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "SELECT * FROM check_database_health();"

# Check for duplicate FKs
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT COUNT(*) as duplicate_fks FROM pg_constraint
  WHERE conname LIKE '%_duplicate%' OR conname LIKE 'fk_%_orders_20%';
"

# Verify new columns
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT column_name FROM information_schema.columns
  WHERE table_name = 'balance_transactions'
  AND column_name IN ('version', 'transaction_hash', 'metadata');
"
```

### Step 6: Application Deployment (15 minutes)
```bash
# 1. Build new application version with updated entities
cd /app/backend
./gradlew clean build -x test


# OR if using Docker Compose
docker-compose up -d --no-deps --build spring-boot-app
```

### Step 7: Monitor Application (30 minutes)
```bash
# 1. Watch application logs
docker-compose logs -f spring-boot-app | grep -E "ERROR|WARN"

# 2. Check database connections
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT application_name, state, query_start, state_change
  FROM pg_stat_activity
  WHERE application_name LIKE 'SMM-%';
"

# 3. Monitor error rate
curl http://localhost:8080/actuator/health | jq .

# 4. Check Liquibase lock
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT * FROM databasechangeloglock;
"
```

### Step 8: Performance Verification (10 minutes)
```bash
# 1. Check query performance
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT query, calls, mean_exec_time, max_exec_time
  FROM pg_stat_statements
  WHERE query LIKE '%balance_transactions%'
  ORDER BY mean_exec_time DESC LIMIT 10;
"

# 2. Verify index usage
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT schemaname, tablename, indexname, idx_scan
  FROM pg_stat_user_indexes
  WHERE idx_scan = 0
  ORDER BY schemaname, tablename;
"
```

## 🔴 ROLLBACK PROCEDURE (If Needed)

### Quick Rollback (5 minutes)
```bash
# 1. Stop application
docker-compose stop spring-boot-app

# 2. Restore database
docker-compose exec postgres pg_restore -U smm_admin -d smm_panel -c $BACKUP_FILE

# 3. Restart with previous version
docker-compose up -d spring-boot-app

# 4. Verify
docker-compose logs --tail=100 spring-boot-app
```

## ✅ POST-IMPLEMENTATION VERIFICATION

### Final Health Check
```bash
# Comprehensive validation script
cat << 'EOF' > validate_fixes.sh
#!/bin/bash
echo "=== Database Fix Validation ==="

# Check enums
echo "1. Checking enum types..."
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT typname FROM pg_type
  WHERE typname IN ('audit_category', 'audit_severity', 'video_processing_status');
"

# Check duplicates
echo "2. Checking for duplicate FKs..."
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT COUNT(*) FROM pg_constraint WHERE conname LIKE '%_duplicate%';
"

# Check new columns
echo "3. Checking new columns..."
docker-compose exec postgres psql -U smm_admin -d smm_panel -c "
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_name = 'balance_transactions'
  AND column_name IN ('version', 'transaction_hash', 'metadata');
"

# Application health
echo "4. Checking application health..."
curl -s http://localhost:8080/actuator/health | jq .status

echo "=== Validation Complete ==="
EOF

chmod +x validate_fixes.sh
./validate_fixes.sh
```

## 📊 SUCCESS CRITERIA

All checks must pass:
- ✅ All 3 enum types created
- ✅ 0 duplicate foreign keys
- ✅ 9 new columns in balance_transactions
- ✅ Application health: UP
- ✅ No ERROR logs in last 10 minutes
- ✅ Database connections < 80% of max
- ✅ Query performance improved or stable

## 📞 EMERGENCY CONTACTS

- **Database Team**: dba@company.com / +1-555-DBA-HELP
- **DevOps On-Call**: +1-555-DEVOPS-1
- **Application Team**: backend@company.com
- **Escalation**: cto@company.com

## 📝 POST-MORTEM TEMPLATE

If issues occur, document:
1. **Time of issue**: _________________
2. **Error message**: _________________
3. **Impact**: _______________________
4. **Resolution**: ___________________
5. **Prevention**: ___________________

---

**Remember**: Take your time, verify each step, and don't hesitate to rollback if anything seems wrong!