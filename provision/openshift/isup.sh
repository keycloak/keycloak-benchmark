#!/usr/bin/env bash
# set -x

set -e

# Default values for variables from Taskfile.yml are not part of .env file, therefore we need to load them manually
KC_NAMESPACE_PREFIX=$(cat .task/var-KC_NAMESPACE_PREFIX)
KC_HOSTNAME_SUFFIX=$(cat .task/var-KC_HOSTNAME_SUFFIX)

if [ -f ./.env ]; then
  source ./.env
fi

# kill all CrashLoopBackOff and ImagePullBackOff pods to trigger a fast restart and not wait Kubernetes
kubectl get pods -n "${KC_NAMESPACE_PREFIX}keycloak" | grep -E "(BackOff|Error)" | tr -s " " | cut -d" " -f1 | xargs -r -L 1 kubectl delete pod -n ${KC_NAMESPACE_PREFIX}keycloak

MAXRETRIES=1200

declare -A SERVICES=( \
 ["keycloak-${KC_NAMESPACE_PREFIX}keycloak.${KC_HOSTNAME_SUFFIX}"]="realms/master/.well-known/openid-configuration" \
)

for SERVICE in "${!SERVICES[@]}"; do
  RETRIES=$MAXRETRIES
  # loop until we connect successfully or failed

  if [ "${SERVICE}" == "keycloak-${KC_NAMESPACE_PREFIX}keycloak.${KC_HOSTNAME_SUFFIX}" ]
  then
    kubectl wait --for=condition=Available --timeout=1200s deployments.apps/keycloak-operator -n "${KC_NAMESPACE_PREFIX}keycloak"
    kubectl wait --for=condition=Ready --timeout=1200s keycloaks.k8s.keycloak.org/keycloak -n "${KC_NAMESPACE_PREFIX}keycloak"
  fi

  until kubectl get ingress -A 2>/dev/null | grep ${SERVICE} >/dev/null && curl -k -f -v https://${SERVICE}/${SERVICES[${SERVICE}]} >/dev/null 2>/dev/null
  do
    RETRIES=$(($RETRIES - 1))
    if [ $RETRIES -eq 0 ]
    then
        echo "Failed to connect"
        exit 1
    fi
    # wait a bit
    if [ "$GITHUB_ACTIONS" == "" ]; then
      echo -n "."
    fi
    sleep 5
  done

  echo https://${SERVICE}/ is up
done
