#!/bin/sh -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $SCRIPT_DIR

## install operator

CNPG_VERSION=${CNPG_VERSION:-"1.28.0"}
CNPG_VERSION_MINOR=${CNPG_VERSION%.*}

kubectl apply --server-side -f https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-$CNPG_VERSION_MINOR/releases/cnpg-$CNPG_VERSION.yaml
kubectl -n cnpg-system rollout status deployment cnpg-controller-manager


## install database cluster

CNPG_NAMESPACE=${CNPG_NAMESPACE:-cnpg-keycloak}
CNPG_INSTANCES=${CNPG_INSTANCES:-1}

kubectl create ns $CNPG_NAMESPACE || true
kubectl -n $CNPG_NAMESPACE apply -f <(cat cluster.yaml | envsubst)
kubectl -n $CNPG_NAMESPACE apply -f pod-monitor.yaml
kubectl -n $CNPG_NAMESPACE wait --for=condition=Ready --timeout=300s cluster cnpg-keycloak 


## set up secrets for Keycloak

KEYCLOAK_NAMESPACE=keycloak

kubectl create ns $KEYCLOAK_NAMESPACE || true

### keycloak db secret (db access)
if kubectl -n $KEYCLOAK_NAMESPACE get secret keycloak-db-secret; then
  kubectl -n $KEYCLOAK_NAMESPACE delete secret keycloak-db-secret
fi
secret=$(kubectl get -n $CNPG_NAMESPACE secret cnpg-keycloak-app -ojson)
u=$(echo "$secret" | jq -r .data.username | base64 -d)
p=$(echo "$secret" | jq -r .data.password | base64 -d)
kubectl -n $KEYCLOAK_NAMESPACE create secret generic keycloak-db-secret --from-literal="username=$u" --from-literal="password=$p"

### ca cert (trust)
kubectl -n $CNPG_NAMESPACE get secrets cnpg-keycloak-ca -ojson | jq -r '.data."ca.crt"' | base64 -d > cnpg-keycloak-ca-cert.pem
if kubectl -n $KEYCLOAK_NAMESPACE get configmap cnpg-keycloak-ca; then
  kubectl -n $KEYCLOAK_NAMESPACE delete configmap cnpg-keycloak-ca
fi
kubectl -n $KEYCLOAK_NAMESPACE create configmap cnpg-keycloak-ca --from-file cert.pem=./cnpg-keycloak-ca-cert.pem
rm cnpg-keycloak-ca-cert.pem
