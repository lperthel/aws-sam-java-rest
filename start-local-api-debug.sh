#!/bin/bash
echo "🔍 Checking if DynamoDB Local is running on port 8000..."
if ! nc -z localhost 8000; then
  echo "❌ DynamoDB Local is not running on port 8000. Start Docker first."
  exit 1
fi
echo "✅ DynamoDB is up."


sam local start-api \
  --debug-port 5850 \
  --debug-function HealthCheckFunction \
  --skip-pull-image \
  --warm-containers EAGER \
  --debug