#!/usr/bin/env bash
echo "going to put in an order..."
export DF_HOST=${1:-localhost:8082}
curl \
  -v \
  -H "Accept: application/json" \
  -H "Content-Type:application/json" \
  -X POST --data '{"amount": 2, "username":"jlong" }' http://$DF_HOST

