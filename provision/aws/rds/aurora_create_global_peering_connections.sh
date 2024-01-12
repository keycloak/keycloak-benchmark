#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -z "${AURORA_GLOBAL_CLUSTER}" ]; then
  echo "AURORA_GLOBAL_CLUSTER variable must be set"
  exit 1
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_global_common.sh

AURORA_GLOBAL_REGIONS=$(globalClusterRegions ${AURORA_GLOBAL_CLUSTER})
GLOBAL_REGIONS=(${AURORA_GLOBAL_REGIONS})
for (( i = 0 ; i < ${#GLOBAL_REGIONS[@]} ; i++ )) ; do
  REGION=${GLOBAL_REGIONS[i]}
  export AURORA_CLUSTER=${AURORA_GLOBAL_CLUSTER}-${REGION}
  export AURORA_REGION=${REGION}
  ${SCRIPT_DIR}/aurora_create_peering_connection.sh || true
done
