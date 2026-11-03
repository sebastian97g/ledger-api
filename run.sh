#!/usr/bin/env bash
set -euo pipefail

echo "=== Ledger API: Build, Start, Test ==="

# Build
echo "[1/4] Building..."
./mvnw clean package -DskipTests -q

# Build Docker image
echo "[2/4] Building Docker image..."
docker build -f docker/Dockerfile -t ledger-api:latest .

# Start services
echo "[3/4] Starting services..."
cd docker
docker-compose up -d
cd ..

# Wait for health
echo "Waiting for services to be healthy..."
attempt=0
max=30
until curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"' 2>/dev/null; do
  attempt=$((attempt+1))
  if [ $attempt -ge $max ]; then
    echo "ERROR: ledger-1 did not become healthy in time"
    docker/docker-compose logs ledger-1
    docker/docker-compose down
    exit 1
  fi
  sleep 5
done
echo "Services healthy."

# Run tests
echo "[4/4] Running tests..."
./mvnw verify -q

echo "=== All done ==="
