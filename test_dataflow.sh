#!/usr/bin/env bash
echo "going to put in an order..."
curl \
  -v \
  -H "Accept: application/json" \
  -H "Content-Type:application/json" \
  -X POST --data '{"amount": 2, "username":"jlong" }' http://34.121.78.28:8080

