#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function arnToRegion() {
  arn=$1
  arrIN=(${arn//:/ })
  echo ${arrIN[3]}
}

function keepAlive() {
  aws rds describe-db-clusters \
    --region $1 \
    --db-cluster-identifier $2 \
    --query DBClusters[*].TagList[?key=='keepalive'] \
    --output json \
    | jq -c '.[]' \
    | jq length
}

# Removes all Aurora DB clusters that are not tagged with the key "keepalive"
# To tag an Aurora Cluster with "keepalive" execute:
# `aws rds add-tags-to-resource --resource-name <arn> --tags Key=keepalive --region <region>`
# Where the `<arn>` is the arn of the DB cluster

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

# Define a default region for global-cluster commands. This can be any region, but is required to prevent 'Invalid endpoint'
# errors
AWS_REGION=${AWS_REGION:-"eu-west-1"}

# Remove Single Region Aurora Clusters
REGIONS=$(aws ec2 describe-regions \
    --query "Regions[*].RegionName" \
    --output text
)
for REGION in ${REGIONS}; do
    # TODO: this lists instances, not cluster, so it might try to delete the clusters twice
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

            KEEP_ALIVE=$(keepAlive ${REGION} ${AURORA_CLUSTER})
            if [ "${KEEP_ALIVE}" == "0" ]; then
                export AURORA_REGION=${REGION}
                export RUNNER_DEBUG=1
                unset AURORA_SECURITY_GROUP_NAME
                unset AURORA_SUBNET_GROUP_NAME
                ${SCRIPT_DIR}/aurora_delete.sh
            fi
        done
    fi
done
