#!/usr/bin/env bash
set -e
set -o pipefail

export PROJECT_ID=${GCLOUD_PROJECT:-pgtm-jlong}
export ROOT_DIR=$(cd $(dirname $0) && pwd)

cd $ROOT_DIR
cd ../..
pwd

export APP_NAME=points
export GCR_IMAGE_NAME=gcr.io/${PROJECT_ID}/${APP_NAME}
#docker rmi $(docker images -a -q)
mvn clean package spring-boot:build-image

image_id=$(docker images -q $APP_NAME)

echo "tagging ${GCR_IMAGE_NAME}"
docker tag "${image_id}" $GCR_IMAGE_NAME

echo "pushing ${image_id} to $GCR_IMAGE_NAME "
docker push $GCR_IMAGE_NAME

echo "deploying to Kubernetes"
kubectl delete deploy/points -n bk || echo "no deployment to delete..."
kubectl apply -f ./k8s


