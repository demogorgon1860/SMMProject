#!/bin/bash
set -e

echo "🔍 ВЕРИФИКАЦИЯ ИСПРАВЛЕНИЙ SMM PANEL"
echo "===================================="

# 1. Проверка backend компиляции
echo "1. Проверка Backend компиляции..."
cd backend
./gradlew build --no-daemon
if [ $? -eq 0 ]; then
    echo "✅ Backend компилируется успешно"
else
    echo "❌ Backend НЕ компилируется"
    exit 1
fi

# 2. Проверка frontend компиляции
echo "2. Проверка Frontend компиляции..."
cd ../frontend
npm install
npm run build
if [ $? -eq 0 ]; then
    echo "✅ Frontend компилируется успешно"
else
    echo "❌ Frontend НЕ компилируется"
    exit 1
fi

# 3. Проверка Docker build
echo "3. Проверка Docker образов..."
cd ..
docker-compose build --no-cache
if [ $? -eq 0 ]; then
    echo "✅ Docker образы собираются успешно"
else
    echo "❌ Docker образы НЕ собираются"
    exit 1
fi

echo "🎉 ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ УСПЕШНО!"