#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

function custom_date() {
    echo "$(date '+%Y%m%d-%H%M%S')"
}

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

CLUSTER_NAME=${CLUSTER_NAME:-$(whoami)}
if [ -z "$CLUSTER_NAME" ]; then echo "Variable CLUSTER_NAME needs to be set."; exit 1; fi
if [ -z "$REGION" ]; then echo "Variable REGION needs to be set."; exit 1; fi

# Cleanup might fail if Aurora/EFS hasn't been configured for the cluster. Ignore any failures and continue
./rds/aurora_delete_peering_connection.sh || true
./rosa_efs_delete.sh || true

cd ${SCRIPT_DIR}/../opentofu/modules/rosa/hcp
WORKSPACE=${CLUSTER_NAME}-${REGION}
./../../../destroy.sh ${WORKSPACE}

LOG_DIR="${SCRIPT_DIR}/logs/${CLUSTER_NAME}"
mkdir -p ${LOG_DIR}
cd ${LOG_DIR}
rosa logs uninstall --debug -c ${CLUSTER_NAME} > "$(custom_date)_delete-cluster.log"
