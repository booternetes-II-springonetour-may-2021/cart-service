#!/usr/bin/env bash

def_host=localhost:8080
my_host=${1:-${def_host}}

echo "what coffees are on order?..."
curl http://${my_host}/cart/coffees

echo "going to put in an order..."
curl \
  -H "Accept: application/json" \
  -H "Content-Type:application/json" \
  -X POST --data '{"coffee": "Sumatra", "username":"jlong" , "quantity": 2 }' http://${my_host}/cart/orders
