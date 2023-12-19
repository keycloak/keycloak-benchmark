#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

CLUSTER_NAME=${CLUSTER_NAME:-$(whoami)}
if [ -z "$CLUSTER_NAME" ]; then echo "Variable CLUSTER_NAME needs to be set."; exit 1; fi

# Cleanup might fail if Aurora/EFS hasn't been configured for the cluster. Ignore any failures and continue
./rds/aurora_delete_peering_connection.sh || true
./rosa_efs_delete.sh || true

function custom_date() {
    echo "$(date '+%Y%m%d-%H%M%S')"
}

CLUSTER_ID=$(rosa describe cluster --cluster "$CLUSTER_NAME" | grep -oPm1 "^ID:\s*\K\w+")
echo "CLUSTER_ID: $CLUSTER_ID"

rosa delete admin --cluster $CLUSTER_ID --yes || true

rosa delete cluster --cluster $CLUSTER_ID --yes

mkdir -p "logs/${CLUSTER_NAME}"

echo "Waiting for cluster uninstallation to finish."
rosa logs uninstall --cluster $CLUSTER_ID --watch --tail=1000000 > "logs/${CLUSTER_NAME}/$(custom_date)_delete-cluster.log"

echo "Cluster uninstalled."

rosa delete operator-roles --cluster $CLUSTER_ID --mode auto --yes > "logs/${CLUSTER_NAME}/$(custom_date)_delete-operator-roles.log" || true
rosa delete oidc-provider --cluster $CLUSTER_ID --mode auto --yes > "logs/${CLUSTER_NAME}/$(custom_date)_delete-oidc-provider.log"
