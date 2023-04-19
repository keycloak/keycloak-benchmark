#!/usr/bin/env bash
# set -x

source .env

# kill all CrashLoopBackOff and ImagePullBackOff pods to trigger a fast restart and not wait Kubernetes
kubectl get pods -n keycloak | grep -E "(BackOff|Error)" | tr -s " " | cut -d" " -f1 | xargs -r -L 1 kubectl delete pod -n keycloak

MAXRETRIES=600

declare -A SERVICES=( \
 ["keycloak.${KC_HOSTNAME_SUFFIX}"]="realms/master/.well-known/openid-configuration" \
)

for SERVICE in "${!SERVICES[@]}"; do
  RETRIES=$MAXRETRIES
  # loop until we connect successfully or failed

  if [ "${SERVICE}" == "keycloak.${KC_HOSTNAME_SUFFIX}" ]
  then
    until [ "$(kubectl get keycloaks.k8s.keycloak.org/keycloak -n keycloak -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" == "true" ]
    do
      RETRIES=$(($RETRIES - 1))
      if [ $RETRIES -eq 0 ]
      then
          kubectl get keycloak/keycloak -n keycloak -o jsonpath='{.status}'
          echo
          echo "Failed waiting for keycloak operator status to become ready"
          exit 1
      fi
      # wait a bit
      if [ "$GITHUB_ACTIONS" == "" ]; then
        echo -n "."
      fi
      sleep 5
    done
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
