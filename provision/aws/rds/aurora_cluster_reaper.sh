#!/bin/bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

REGIONS=$(aws ec2 describe-regions \
    --query "Regions[*].RegionName" \
    --output text
)
for REGION in ${REGIONS}; do
    DB_INSTANCES=$(aws rds describe-db-instances \
        --region ${REGION} \
        --filters Name=engine,Values=${AURORA_ENGINE} \
        --output json \
        | jq .DBInstances
    )

    NO_INSTANCES=$(echo ${DB_INSTANCES} | jq length)
    if [ ${NO_INSTANCES} != "0" ]; then
        echo ${DB_INSTANCES} | jq -c '.[]' | while read i; do
            export AURORA_CLUSTER=$(echo $i | jq -r .DBClusterIdentifier)
            export AURORA_INSTANCE=$(echo $i | jq -r .DBInstanceIdentifier)
            sh ${SCRIPT_DIR}/aurora_delete.sh
        done
    fi
done
