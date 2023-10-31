#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

# https://cloud.redhat.com/blog/using-vpc-peering-to-connect-an-openshift-service-on-an-aws-rosa-cluster-to-an-amazon-rds-mysql-database-in-a-different-vpc
EXISTING_INSTANCES=$(aws rds describe-db-instances \
  --query "DBInstances[?starts_with(DBInstanceIdentifier, '${AURORA_CLUSTER}')].DBInstanceIdentifier" \
  --output text
)
if [ -n "${EXISTING_INSTANCES}" ]; then
  echo "Aurora instances '${EXISTING_INSTANCES}' already exist in the '${AWS_REGION}' region"
  exit 0
fi

# Create the Aurora VPC
AURORA_VPC=$(aws ec2 create-vpc \
  --cidr-block ${AURORA_VPC_CIDR} \
  --tag-specifications "ResourceType=vpc, Tags=[{Key=AuroraCluster,Value=${AURORA_CLUSTER}}]" \
  --output json \
  | jq -r '.Vpc.VpcId'
)

# Create the Aurora Subnets
SUBNET_A=$(aws ec2 create-subnet \
  --availability-zone "${AWS_REGION}a" \
  --vpc-id ${AURORA_VPC} \
  --cidr-block ${AURORA_SUBNET_A_CIDR} \
  --output json \
  | jq -r '.Subnet.SubnetId'
)

SUBNET_B=$(aws ec2 create-subnet \
  --availability-zone "${AWS_REGION}b" \
  --vpc-id ${AURORA_VPC} \
  --cidr-block ${AURORA_SUBNET_B_CIDR} \
  --output json \
  | jq -r '.Subnet.SubnetId'
)

AURORA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
  --filters Name=vpc-id,Values=${AURORA_VPC} \
  --output json \
  | jq -r '.RouteTables[0].RouteTableId'
)

aws ec2 associate-route-table \
  --route-table-id ${AURORA_PUBLIC_ROUTE_TABLE_ID} \
  --subnet-id ${SUBNET_A}

aws ec2 associate-route-table \
  --route-table-id ${AURORA_PUBLIC_ROUTE_TABLE_ID} \
  --subnet-id ${SUBNET_B}

# Create Aurora Subnet Group
aws rds create-db-subnet-group \
  --db-subnet-group-name ${AURORA_SUBNET_GROUP_NAME} \
  --db-subnet-group-description "Aurora DB Subnet Group" \
  --subnet-ids ${SUBNET_A} ${SUBNET_B}

# Create an Aurora VPC Security Group
AURORA_SECURITY_GROUP_ID=$(aws ec2 create-security-group \
  --group-name ${AURORA_SECURITY_GROUP_NAME} \
  --description "Aurora DB Security Group" \
  --vpc-id ${AURORA_VPC} \
  --output json \
  | jq -r '.GroupId'
)

if [ -z ${AURORA_GLOBAL_CLUSTER_BACKUP} ]; then
  AURORA_MASTER_USER="--master-username ${AURORA_USERNAME} --master-user-password ${AURORA_PASSWORD}"
  AURORA_DATABASE_NAME="--database-name keycloak"
fi

# Create the Aurora DB cluster and instance
aws rds create-db-cluster \
    --db-cluster-identifier ${AURORA_CLUSTER} \
    ${AURORA_DATABASE_NAME} \
    --engine ${AURORA_ENGINE} \
    --engine-version ${AURORA_ENGINE_VERSION} \
    ${AURORA_MASTER_USER} \
    --vpc-security-group-ids ${AURORA_SECURITY_GROUP_ID} \
    --db-subnet-group-name ${AURORA_SUBNET_GROUP_NAME} \
    ${AURORA_GLOBAL_CLUSTER_IDENTIFIER}

# For now only two AZs in each region are supported due to the two subnets created above
readarray -t AZS < <(echo ${AWS_REGION}a; echo ${AWS_REGION}b)

for i in $( seq ${AURORA_INSTANCES} ); do
  aws rds create-db-instance \
    --db-cluster-identifier ${AURORA_CLUSTER} \
    --db-instance-identifier "${AURORA_CLUSTER}-instance-${i}" \
    --db-instance-class ${AURORA_INSTANCE_CLASS} \
    --engine ${AURORA_ENGINE} \
    --availability-zone "${AZS[$(((i - 1) % ${#AZS[@]}))]}"
done

for i in $( seq ${AURORA_INSTANCES} ); do
  aws rds wait db-instance-available --db-instance-identifier "${AURORA_CLUSTER}-instance-${i}"
done
