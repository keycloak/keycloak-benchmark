#!/usr/bin/env bash
# Script simulating different xsite failover scenarios
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function activeClusterDown() {
  DOMAIN=$1
  CLIENT_IPS=$(dig +short client.${DOMAIN} | sort)
  PRIMARY_IPS=$(dig +short primary.${DOMAIN} | sort)
  BACKUP_IPS=$(dig +short backup.${DOMAIN} | sort)

  [[ "${CLIENT_IPS}" == "${BACKUP_IPS}" && "${CLIENT_IPS}" != "${PRIMARY_IPS}" ]]
  return
}

function scaleDownResource() {
  kubectl -n $1 scale --replicas=0 $2
  kubectl -n $1 rollout status --watch --timeout=600s $2
}

# Removes the Keycloak aws-health-route so that Route53 will eventually failover
function killHealthRoute() {
  kubectl -n ${PROJECT} delete route aws-health-route || true
}

# Remove all Keycloak routes so that Route53 will failover from the active to the passive cluster and the old DNS ips will fail
function killKeycloakRoutes() {
  scaleDownResource ${PROJECT} deployment/keycloak-operator
  kubectl -n ${PROJECT} delete ingress keycloak-ingress || true
  killHealthRoute
}

# Delete the Keycloak + Infinispan pods to simulate cluster crash
function killKeycloakCluster() {
  scaleDownResource openshift-operators deployment/infinispan-operator-controller-manager
  scaleDownResource ${PROJECT} deployment/keycloak-operator
  kubectl -n ${PROJECT} delete pods --all --force --grace-period=0
  kubectl -n ${PROJECT} delete statefulset --all
}

# Delete the Infinispan GossipRouter
function killGossipRouter() {
  scaleDownResource openshift-operators deployment/infinispan-operator-controller-manager
  kubectl -n ${PROJECT} delete pods -l app=infinispan-router-pod --force --grace-period=0
  kubectl -n ${PROJECT} delete deployment/infinispan-router || true
}

# Scale Infinispan and Keycloak Operators so that the original cluster is recreated
function reviveKeycloakCluster() {
  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Running Recovery scenario - ${RECOVERY_MODE}\033[0m"
  cat << EOF | kubectl -n ${PROJECT} apply -f -
  apiVersion: route.openshift.io/v1
  kind: Route
  metadata:
    name: aws-health-route
  spec:
    host: "$1.${DOMAIN}"
    port:
      targetPort: https
    tls:
      insecureEdgeTerminationPolicy: Redirect
      termination: passthrough
    to:
      kind: Service
      name: keycloak-service
EOF
  kubectl -n openshift-operators scale --replicas=1 deployment/infinispan-operator-controller-manager
  kubectl -n ${PROJECT} scale --replicas=1 deployment/keycloak-operator
  kubectl -n ${PROJECT} rollout status --watch --timeout=600s statefulset/infinispan
  kubectl -n ${PROJECT} rollout status --watch --timeout=600s statefulset/keycloak
  exit
}

function waitForFailover() {
  START=$(date +%s)
  until activeClusterDown ${DOMAIN}
  do
    sleep 0.1
  done
  END=$(date +%s)
  DIFF=$(( END - START ))

  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Route53 took ${DIFF} seconds to failover\033[0m"
}

function clusterFailover() {
  killKeycloakCluster
}

: ${PROJECT:="runner-keycloak"}
: ${FAILOVER_DELAY:=60}

PROJECT=${PROJECT:-"runner-keycloak"}

if [ -z "${RECOVERY_MODE}" ] && [ -z "${FAILOVER_MODE}" ]; then
  echo "RECOVERY_MODE or FAILOVER_MODE env must be defined"
  exit 1
fi

if [ -z "${DOMAIN}" ]; then
  echo "DOMAIN env must be defined"
  exit 1
fi

if [ -n "${RECOVERY_MODE}" ]; then
  if [ "${RECOVERY_MODE^^}" == "ACTIVE" ]; then
    reviveKeycloakCluster primary
  elif [ "${RECOVERY_MODE^^}" == "PASSIVE" ]; then
    reviveKeycloakCluster backup
  else
    echo "Unknown RECOVERY_MODE=${RECOVERY_MODE}"
    exit 1
  fi
fi

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Entering Failover mode, with an initial delay of ${FAILOVER_DELAY} seconds\033[0m"
sleep ${FAILOVER_DELAY}
echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Running Failover scenario - ${FAILOVER_MODE}\033[0m"

CLIENT_IPS=$(dig +short client.${DOMAIN} | sort)
PRIMARY_IPS=$(dig +short primary.${DOMAIN} | sort)
BACKUP_IPS=$(dig +short backup.${DOMAIN} | sort)

if [ "${FAILOVER_MODE^^}" == "HEALTH_PROBE" ]; then
  killHealthRoute
elif [ "${FAILOVER_MODE^^}" == "KEYCLOAK_ROUTES" ]; then
  killKeycloakRoutes
elif [ "${FAILOVER_MODE^^}" == "CLUSTER_FAIL" ]; then
  killKeycloakCluster
elif [ "${FAILOVER_MODE^^}" == "GOSSIP_ROUTER_FAIL" ]; then
  killGossipRouter
  exit
fi

waitForFailover
