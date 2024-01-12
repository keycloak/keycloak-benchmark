#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

aws rds describe-db-clusters \
  --db-cluster-identifier ${AURORA_CLUSTER} \
  --query 'DBClusters[*].Endpoint' \
  --output text
