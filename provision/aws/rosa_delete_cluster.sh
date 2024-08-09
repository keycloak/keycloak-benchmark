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

# Explicitly delete OSD Network Verifier that's sometimes created as it prevents VPC being deleted
OSD_VERIFIER_SG=$(aws ec2 describe-security-groups \
  --filters Name=tag:Name,Values=osd-network-verifier \
  --filters Name=tag:cluster,Values=${CLUSTER_NAME} \
  --query 'SecurityGroups[*].GroupId' \
  --region ${REGION} \
  --output text)
if [ -n "${OSD_VERIFIER_SG}" ]; then
  aws ec2 delete-security-group \
    --group-id ${OSD_VERIFIER_SG} \
    --region ${REGION} \
    --no-cli-pager || true
fi

#Creating Logs directory for each cluster
LOG_DIR="${SCRIPT_DIR}/logs/${CLUSTER_NAME}"
mkdir -p ${LOG_DIR}
cd ${LOG_DIR}
echo "Starting to watch cluster uninstall logs."
timeout -k 30m 30m bash -c "rosa logs uninstall -c ${CLUSTER_NAME} --watch --debug &> $(custom_date)_delete-cluster.log" &

cd ${SCRIPT_DIR}/../opentofu/modules/rosa/hcp
WORKSPACE=${CLUSTER_NAME}-${REGION}
timeout -k 30m 30m bash -c "./../../../destroy.sh ${WORKSPACE}" &
DESTROY_PROC_ID=$!

if wait "${DESTROY_PROC_ID}"; then
    echo "Cluster uninstalled successfully."
else
    echo "Timeout encountered deleting cluster"
fi
