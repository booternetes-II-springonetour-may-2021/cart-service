#!/usr/bin/env bash
set -e
set -o pipefail

export PROJECT_ID=${GCLOUD_PROJECT:-pgtm-jlong}
export ROOT_DIR=$(cd $(dirname $0) && pwd)

cd $ROOT_DIR
cd ../..
pwd

export APP_NAME=cart
export GCR_IMAGE_NAME=gcr.io/${PROJECT_ID}/${APP_NAME}
mvn clean package spring-boot:build-image

image_id=$(docker images -q $APP_NAME)

echo "tagging ${GCR_IMAGE_NAME}"
docker tag "${image_id}" $GCR_IMAGE_NAME

echo "pushing ${image_id} to $GCR_IMAGE_NAME "
docker push $GCR_IMAGE_NAME



#####
echo "Deploying to Kubernetes"

kubectl delete secrets mysql-secrets || echo "no secrets to delete..."
kubectl delete deploy/${APP_NAME} -n bk || echo "no deployment to delete..."

export MYSQL_ROOT_PASSWORD=bk
export MYSQL_PASSWORD=$MYSQL_ROOT_PASSWORD

##
## Deploy
kubectl apply -f <(echo "
---
apiVersion: v1
kind: Secret
metadata:
  name: cart-mysql-secrets
type: Opaque
stringData:
  MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
  MYSQL_DATABASE: bk
  MYSQL_USER: bk
  MYSQL_PASSWORD: ${MYSQL_PASSWORD}
")
kubectl apply -f ./k8s


