#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

AURORA_VPC=$(aws ec2 describe-vpcs \
  --filters "Name=tag:AuroraCluster,Values=${AURORA_CLUSTER}" \
  --query "Vpcs[*].VpcId" \
  --region ${AURORA_REGION} \
  --output text
)

# Delete the Aurora DB cluster and instances
for i in $( aws rds describe-db-clusters --db-cluster-identifier ${AURORA_CLUSTER} --region ${AURORA_REGION} --output json | jq -r .DBClusters[0].DBClusterMembers[].DBInstanceIdentifier ); do
  echo "Deleting Aurora DB instance ${i}"
  aws rds delete-db-instance --db-instance-identifier "${i}" --skip-final-snapshot --region ${AURORA_REGION} || true
done

aws rds delete-db-cluster \
  --db-cluster-identifier ${AURORA_CLUSTER} \
  --region ${AURORA_REGION} \
  --skip-final-snapshot \
  || true

for i in $( aws rds describe-db-clusters --db-cluster-identifier ${AURORA_CLUSTER} --output json --region ${AURORA_REGION} | jq -r .DBClusters[0].DBClusterMembers[].DBInstanceIdentifier ); do
  aws rds wait db-instance-deleted --db-instance-identifier --region ${AURORA_REGION} "${i}"
done

aws rds wait db-cluster-deleted --db-cluster-identifier ${AURORA_CLUSTER} --region ${AURORA_REGION}

# Delete the Aurora subnet group
aws rds delete-db-subnet-group --db-subnet-group-name ${AURORA_SUBNET_GROUP_NAME} --region ${AURORA_REGION} || true

# Delete the Aurora subnets
AURORA_SUBNETS=$(aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=${AURORA_VPC}" \
  --query "Subnets[*].SubnetId" \
  --region ${AURORA_REGION} \
  --output text
)
for AURORA_SUBNET in ${AURORA_SUBNETS}; do
  aws ec2 delete-subnet --region ${AURORA_REGION} --subnet-id ${AURORA_SUBNET}
done

# Delete the Aurora VPC Security Group
AURORA_SECURITY_GROUP_ID=$(aws ec2 describe-security-groups \
  --filters "Name=vpc-id,Values=${AURORA_VPC}" "Name=group-name,Values=${AURORA_SECURITY_GROUP_NAME}" \
  --query "SecurityGroups[*].GroupId" \
  --region ${AURORA_REGION} \
  --output text
)
if [ -n "${AURORA_SECURITY_GROUP_ID}" ]; then
  aws ec2 delete-security-group --group-id ${AURORA_SECURITY_GROUP_ID} --region ${AURORA_REGION}
fi

# Delete the Aurora VPC, retrying 5 times in case that dependencies are not removed instantly
n=0
until [ "$n" -ge 20 ]
do
   aws ec2 delete-vpc --vpc-id ${AURORA_VPC} --region ${AURORA_REGION} && break
   n=$((n+1))
   echo "Unable to remove VPC ${AURORA_VPC}. Attempt ${n}"
   sleep 10
done
