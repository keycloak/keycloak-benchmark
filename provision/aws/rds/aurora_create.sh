#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

# https://cloud.redhat.com/blog/using-vpc-peering-to-connect-an-openshift-service-on-an-aws-rosa-cluster-to-an-amazon-rds-mysql-database-in-a-different-vpc
EXISTING_INSTANCE=$(aws rds describe-db-instances \
  --query 'DBInstances[*].[DBInstanceIdentifier]'  \
  --filters Name=db-instance-id,Values=${AURORA_INSTANCE} \
  --output text
)
if [ -n "${EXISTING_INSTANCE}" ]; then
  echo "Aurora instance '${AURORA_INSTANCE}:${AWS_REGION}' already exists"
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

# Create the Aurora DB cluster and instance
aws rds create-db-cluster \
    --db-cluster-identifier ${AURORA_CLUSTER} \
    --database-name keycloak \
    --engine ${AURORA_ENGINE} \
    --engine-version ${AURORA_ENGINE_VERSION} \
    --master-username ${AURORA_USERNAME} \
    --master-user-password ${AURORA_PASSWORD} \
    --vpc-security-group-ids ${AURORA_SECURITY_GROUP_ID} \
    --db-subnet-group-name ${AURORA_SUBNET_GROUP_NAME}

aws rds create-db-instance \
  --db-cluster-identifier ${AURORA_CLUSTER} \
  --db-instance-identifier ${AURORA_INSTANCE} \
  --db-instance-class ${AURORA_INSTANCE_CLASS} \
  --engine ${AURORA_ENGINE}

aws rds wait db-instance-available --db-instance-identifier ${AURORA_INSTANCE}
