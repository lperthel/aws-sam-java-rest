#!/bin/bash

echo "🔍 Checking if DynamoDB Local is running on port 8000..."
if ! nc -z localhost 8000; then
  echo "❌ DynamoDB Local is not running on port 8000. Start Docker first."
  exit 1
fi
echo "✅ DynamoDB is up."


mvn clean 
mkdir -p target 
echo "🔨 Running sam build..."
sam build --debug 2>&1 | tee target/sam-build.log
if [ $? -ne 0 ]; then
  echo "❌ Build failed. See target/sam-build.log"
  exit 1
fi
echo "✅ Build succeeded. Starting API at http://127.0.0.1:3000"

sam local start-api \
  --debug-port 5850 \
  --debug-function HealthCheckFunction \
  --skip-pull-image \
  --warm-containers EAGER \
  --debug
