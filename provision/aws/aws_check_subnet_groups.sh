#!/usr/bin/env bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function check_db_subnet_group_usage() {
    local db_subnet_group_name=$1
    local region=$2
    # Check for any RDS instances associated with the subnet group
    rds_instances=$(aws rds describe-db-instances --region "$region" --query "DBInstances[?DBSubnetGroup.DBSubnetGroupName=='${db_subnet_group_name}'].DBInstanceIdentifier" --output text)
    # Check for any Aurora clusters associated with the subnet group
    rds_clusters=$(aws rds describe-db-clusters --region "$region" --query "DBClusters[?DBSubnetGroup=='${db_subnet_group_name}'].DBClusterIdentifier" --output text)
    [[ -z $rds_instances && -z $rds_clusters ]]
}

EXIT_CODE=0

# Iterate through each DB subnet group and delete if not in use
for region in $(aws account list-regions --query "Regions[?RegionOptStatus != 'DISABLED'].RegionName" --output text); do
    db_subnet_groups=$(aws rds describe-db-subnet-groups --region "$region" --query "DBSubnetGroups[].DBSubnetGroupName" --output text)
    echo "Looking in AWS Region: $region"
    for db_subnet_group in $db_subnet_groups; do
        #echo "Checking DB Subnet Group: $db_subnet_group"
        if check_db_subnet_group_usage "$db_subnet_group" "$region"; then
            echo "Found an unused DB Subnet Group: $db_subnet_group in $region"
            EXIT_CODE=1
        fi
    done
done

exit "$EXIT_CODE"
