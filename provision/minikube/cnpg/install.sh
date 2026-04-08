#!/bin/bash -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd $SCRIPT_DIR

## openshift-specific prerequisities

if kubectl api-resources --api-group=route.openshift.io; then
  # add privileged security context for the cnpg service account
  oc adm policy add-scc-to-user privileged system:serviceaccount:cnpg-system:cnpg-manager
fi


## install operator

CNPG_VERSION=${CNPG_VERSION:-"1.29.0"}
CNPG_VERSION_MINOR=${CNPG_VERSION%.*}

kubectl apply --server-side -f https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-$CNPG_VERSION_MINOR/releases/cnpg-$CNPG_VERSION.yaml
kubectl -n cnpg-system rollout status deployment cnpg-controller-manager


## install database cluster

export CNPG_NAMESPACE=cnpg-keycloak
export CNPG_INSTANCES=${CNPG_INSTANCES:-3}
export CNPG_STORAGE_SIZE=${CNPG_STORAGE_SIZE:-1Gi}
export CNPG_MAX_CONNECTIONS=${CNPG_MAX_CONNECTIONS:-100}

kubectl create ns $CNPG_NAMESPACE || true
kubectl -n $CNPG_NAMESPACE apply -f <(cat cluster.yaml | envsubst)
kubectl -n $CNPG_NAMESPACE apply -f pod-monitor.yaml
kubectl -n $CNPG_NAMESPACE wait --for=condition=Ready --timeout=300s cluster cnpg-keycloak


## set up secrets for Keycloak

KEYCLOAK_NAMESPACE=${KEYCLOAK_NAMESPACE:-keycloak}

kubectl create ns $KEYCLOAK_NAMESPACE || true

### keycloak db secret (db access)
if kubectl -n $KEYCLOAK_NAMESPACE get secret keycloak-db-secret; then
  kubectl -n $KEYCLOAK_NAMESPACE delete secret keycloak-db-secret
fi
kubectl get secret cnpg-keycloak-app --namespace $CNPG_NAMESPACE -o go-template='
apiVersion: v1
kind: Secret
metadata:
  name: keycloak-db-secret
type: Opaque
data:
  username: {{ .data.username }}
  password: {{ .data.password }}
' | kubectl apply -n $KEYCLOAK_NAMESPACE -f -

### ca cert (trust)
if kubectl -n $KEYCLOAK_NAMESPACE get configmap cnpg-keycloak-ca; then
  kubectl -n $KEYCLOAK_NAMESPACE delete configmap cnpg-keycloak-ca
fi
kubectl --namespace $KEYCLOAK_NAMESPACE create configmap cnpg-keycloak-ca \
  --from-literal=cert.pem="$(kubectl --namespace $CNPG_NAMESPACE get secrets cnpg-keycloak-ca -o jsonpath='{.data.ca\.crt}' | base64 -d)"
