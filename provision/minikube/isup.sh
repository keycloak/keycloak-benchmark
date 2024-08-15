#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

# when no arguments was given
if [ $# -eq 0 ]
then
  HOST=$(minikube ip).nip.io
else
  HOST=$0
fi

# kill all CrashLoopBackOff and ImagePullBackOff pods to trigger a fast restart and not wait Kubernetes
kubectl get pods -A | grep -E "(BackOff|Error)" | tr -s " " | cut -d" " -f1-2 | xargs -r -L 1 kubectl delete pod -n

MAXRETRIES=600

declare -A SERVICES=( \
 ["keycloak-keycloak.${HOST}"]="realms/master/.well-known/openid-configuration" \
 ["grafana.${HOST}"]="" \
 ["prometheus.${HOST}"]="" \
 ["jaeger.${HOST}"]="api/services" \
 ["kubebox.${HOST}"]="" \
 ["cryostat.${HOST}"]="" \
 )

for SERVICE in "${!SERVICES[@]}"; do
  RETRIES=$MAXRETRIES
  # loop until we connect successfully or failed

  if [ "${SERVICE}" == "keycloak-keycloak.${HOST}" ]
  then
    kubectl wait --for=condition=Available --timeout=300s deployments.apps/keycloak-operator -n keycloak
    kubectl wait --for=condition=Ready --timeout=300s  keycloak/keycloak -n keycloak
    kubectl wait --for=condition=RollingUpdate=False --timeout=1200s keycloak/keycloak -n keycloak
  fi

  if [[ ${SERVICE} = cryostat* ]] && ! ${KC_CRYOSTAT}; then continue; fi

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

  if [ "${SERVICE}" == "jaeger.${HOST}" ]
  then
    until curl -k -f -v https://${SERVICE}/${SERVICES[${SERVICE}]} -o - 2>/dev/null | grep "jaeger-all-in-one" >/dev/null 2>/dev/null
    do
      RETRIES=$(($RETRIES - 1))
      if [ $RETRIES -eq 0 ]
      then
          echo "Failed to see service jaeger-all-in-one in the list of Jaeger services"
          exit 1
      fi
      # wait a bit
      if [ "$GITHUB_ACTIONS" == "" ]; then
        echo -n "."
      fi
      sleep 5
    done
  fi

  echo https://${SERVICE}/ is up
done
