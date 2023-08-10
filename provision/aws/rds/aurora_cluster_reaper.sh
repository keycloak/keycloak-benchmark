#!/bin/bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

# Removes all Aurora DB clusters that are not tagged with the key "keepalive"
# To tag an Aurora Cluster with "keepalive" execute:
# `aws rds add-tags-to-resource --resource-name <arn> --tags Key=keepalive --region <region>`
# Where the `<arn>` is the arn of the DB cluster

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

            KEEP_ALIVE=$(aws rds describe-db-clusters \
                --region ${REGION} \
                --db-cluster-identifier ${AURORA_CLUSTER} \
                --query DBClusters[*].TagList[?key=='keepalive'] \
                --output json \
                | jq -c '.[]' \
                | jq length
            )
            if [ ${KEEP_ALIVE} == "0" ]; then
                ${SCRIPT_DIR}/aurora_delete.sh
            fi
        done
    fi
done
