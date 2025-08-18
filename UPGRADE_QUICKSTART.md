# Quick Upgrade Guide - Safe Updates Only

## 🚀 Copy-Paste Commands for Safe Upgrades

### Backend (build.gradle)

```gradle
// SAFE UPGRADES - 100% backward compatible
// Copy these exact versions to your build.gradle

plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.12'  // LTS upgrade from 3.1.7
    id 'io.spring.dependency-management' version '1.1.4'
    // REMOVE THIS LINE: id 'org.flywaydb.flyway' version '9.22.3'
    id 'org.liquibase.gradle' version '2.2.1'
    id 'jacoco'
    id 'com.diffplug.spotless' version '6.25.0'
}

dependencies {
    // Update these versions:
    implementation 'io.lettuce:lettuce-core:6.3.2.RELEASE'  // from 6.2.6
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'  // from 0.12.3
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
    
    implementation 'org.seleniumhq.selenium:selenium-java:4.20.0'  // from 4.15.0
    implementation 'org.seleniumhq.selenium:selenium-chrome-driver:4.20.0'
    implementation 'org.seleniumhq.selenium:selenium-support:4.20.0'
    
    compileOnly 'org.projectlombok:lombok:1.18.36'  // from 1.18.30
    annotationProcessor 'org.projectlombok:lombok:1.18.36'
    
    implementation 'org.apache.commons:commons-lang3:3.14.0'  // from 3.13.0
    implementation 'commons-io:commons-io:2.16.1'  // from 2.15.1
    implementation 'commons-validator:commons-validator:1.8.0'  // from 1.7 - SECURITY FIX
    
    implementation 'com.google.guava:guava:33.0.0-jre'  // from 32.1.3-jre
}
```

### Frontend (package.json)

```bash
# Run these commands in frontend directory:

# Safe minor/patch updates
npm install typescript@5.4.5
npm install axios@1.7.7
npm install react-hook-form@7.54.2
npm install date-fns@3.0.0
npm install lucide-react@0.400.0

# Dev dependencies
npm install -D @typescript-eslint/eslint-plugin@6.21.0
npm install -D @typescript-eslint/parser@6.21.0
npm install -D eslint@8.57.0
npm install -D tailwindcss@3.4.15
npm install -D @testing-library/react@14.3.1

# CRITICAL: Migrate React Query v3 to TanStack Query v5
npm uninstall react-query
npm install @tanstack/react-query@5.62.10
npm install -D @tanstack/react-query-devtools@5.62.10
```

### Docker Compose Fixes

```yaml
# docker-compose.yml - Replace 'latest' tags with:

prometheus:
  image: prom/prometheus:v2.54.1  # was: latest

grafana:
  image: grafana/grafana:10.4.10  # was: latest
```

---

## ⚠️ BREAKING CHANGES - Do NOT Apply Without Testing

### React Query Migration (CRITICAL but BREAKING)

```typescript
// OLD (react-query v3)
import { useQuery } from 'react-query';

const { data, isLoading } = useQuery('key', fetchFunction);

// NEW (@tanstack/react-query v5)
import { useQuery } from '@tanstack/react-query';

const { data, isLoading } = useQuery({
  queryKey: ['key'],
  queryFn: fetchFunction,
});
```

### Vite 6 (BREAKING - Config changes)
```bash
# DO NOT RUN YET - Requires config migration
# npm install vite@6.0.5
```

---

## 📋 Upgrade Checklist

### Phase 1: Safe Updates (Do Now)
- [ ] Backup project
- [ ] Update Spring Boot to 3.2.12 LTS
- [ ] Remove Flyway plugin from build.gradle
- [ ] Apply all safe backend dependency updates
- [ ] Apply all safe frontend dependency updates
- [ ] Pin Docker image versions
- [ ] Run tests

### Phase 2: Critical Migrations (Plan Time)
- [ ] Migrate React Query v3 → @tanstack/react-query v5 (2-3 days)
- [ ] Test all API calls after React Query migration
- [ ] Update all useQuery/useMutation calls

### Phase 3: Optional Updates (Later)
- [ ] Consider Vite 6 upgrade (requires config changes)
- [ ] Evaluate Zustand v5 (TypeScript improvements)
- [ ] Plan Spring Boot 3.3.x evaluation (non-LTS)

---

## 🧪 Test Commands After Upgrade

```bash
# Backend
cd backend
./gradlew clean build
./gradlew test

# Frontend  
cd frontend
npm run type-check
npm test
npm run build

# Docker
docker-compose config
docker-compose up -d
```

---

## 🔄 Rollback Plan

If issues occur:
```bash
# Backend
git checkout -- backend/build.gradle

# Frontend
git checkout -- frontend/package.json
rm -rf node_modules package-lock.json
npm install

# Docker
git checkout -- docker-compose.yml
docker-compose down
docker-compose up -d
```

---

## 📞 Support Resources

- Spring Boot 3.2 Migration: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.2-Release-Notes
- TanStack Query Migration: https://tanstack.com/query/latest/docs/react/guides/migrating-to-v5
- Vite Migration: https://vitejs.dev/guide/migration.html

---

## ⏱️ Estimated Time

- **Safe Updates**: 30 minutes
- **Testing**: 1-2 hours
- **React Query Migration**: 2-3 days
- **Full Stack Update**: 1 week

---

*Use this guide for quick, safe upgrades. Always test in development first!*