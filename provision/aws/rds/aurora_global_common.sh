#!/bin/bash
set -e

function arnToRegion() {
  arn=$1
  arrIN=(${arn//:/ })
  echo ${arrIN[3]}
}

function globalClusterRegions() {
  AURORA_GLOBAL_CLUSTER=$1
  GLOBAL_CLUSTER_MEMBERS=$(aws rds describe-global-clusters \
    --query "GlobalClusters[?GlobalClusterIdentifier=='${AURORA_GLOBAL_CLUSTER}'].GlobalClusterMembers[]" \
    --output json
  )

  PRIMARY_CLUSTER_ARN=$(echo ${GLOBAL_CLUSTER_MEMBERS} | jq -r '.[] | select(.IsWriter==true).DBClusterArn')
  BACKUP_CLUSTER_ARNS=$(echo ${GLOBAL_CLUSTER_MEMBERS} | jq -r '.[] | select(.IsWriter==false).DBClusterArn')

  AURORA_GLOBAL_REGIONS=$(arnToRegion ${PRIMARY_CLUSTER_ARN})
  for BACKUP_CLUSTER_ARN in ${BACKUP_CLUSTER_ARNS}; do
    AURORA_GLOBAL_REGIONS="${AURORA_GLOBAL_REGIONS} $(arnToRegion ${BACKUP_CLUSTER_ARN})"
  done
  echo ${AURORA_GLOBAL_REGIONS}
}
