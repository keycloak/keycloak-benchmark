#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

# https://cloud.redhat.com/blog/using-vpc-peering-to-connect-an-openshift-service-on-an-aws-rosa-cluster-to-an-amazon-rds-mysql-database-in-a-different-vpc
EXISTING_INSTANCES=$(aws rds describe-db-instances \
  --query "DBInstances[?starts_with(DBInstanceIdentifier, '${AURORA_CLUSTER}-instance')].DBInstanceIdentifier" \
  --output text
)
if [ -n "${EXISTING_INSTANCES}" ]; then
  echo "Aurora instances '${EXISTING_INSTANCES}' already exist in the '${AWS_REGION}' region"
  exit 0
fi

REGIONS=$(aws ec2 describe-regions \
    --query "Regions[*].RegionName" \
    --output text
)

EXISTING_AURORA_CIDRS=""
for REGION in ${REGIONS}; do
  CIDRS=$(aws ec2 describe-vpcs \
    --filters Name=tag-key,Values=AuroraCluster \
    --query "Vpcs[*].CidrBlock" \
    --region ${REGION} \
    --output text
  )
  if [[ "${EXISTING_AURORA_CIDRS}" != *"${CIDRS}"* ]]; then
    EXISTING_AURORA_CIDRS+=" ${CIDRS}"
  fi
done

if (( $(echo ${EXISTING_AURORA_CIDRS} | wc -l) > 63 )); then
  echo "Maximum number of unique Aurora CIDRS reached"
  echo ${EXISTING_AURORA_CIDRS}
  exit 1
fi

while true; do
  AURORA_VPC_RANGE="192.168.$(shuf -i 0-255 -n 1)"
  AURORA_VPC_CIDR="${AURORA_VPC_RANGE}.0/24"
  if [[ "${EXISTING_MACHINE_CIDRS}" != *"${AURORA_VPC_CIDR}"* ]]; then
    break
  fi
done
AURORA_SUBNET_A_CIDR=${AURORA_VPC_RANGE}.0/26 # 0-63
AURORA_SUBNET_B_CIDR=${AURORA_VPC_RANGE}.64/26 # 64-127
AURORA_SUBNET_C_CIDR=${AURORA_VPC_RANGE}.128/26 # 128-191

# Create the Aurora VPC
AURORA_VPC=$(aws ec2 create-vpc \
  --cidr-block ${AURORA_VPC_CIDR} \
  --tag-specifications "ResourceType=vpc, Tags=[{Key=AuroraCluster,Value=${AURORA_CLUSTER}},{Key=Name,Value=Aurora Cluster ${AURORA_CLUSTER}}]" \
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

SUBNET_C=$(aws ec2 create-subnet \
  --availability-zone "${AWS_REGION}c" \
  --vpc-id ${AURORA_VPC} \
  --cidr-block ${AURORA_SUBNET_C_CIDR} \
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

aws ec2 associate-route-table \
  --route-table-id ${AURORA_PUBLIC_ROUTE_TABLE_ID} \
  --subnet-id ${SUBNET_C}

# Create Aurora Subnet Group
aws rds create-db-subnet-group \
  --db-subnet-group-name ${AURORA_SUBNET_GROUP_NAME} \
  --db-subnet-group-description "Aurora DB Subnet Group" \
  --subnet-ids ${SUBNET_A} ${SUBNET_B} ${SUBNET_C}

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
    ${AURORA_DATABASE_NAME} \
    --engine ${AURORA_ENGINE} \
    --engine-version ${AURORA_ENGINE_VERSION} \
    --vpc-security-group-ids ${AURORA_SECURITY_GROUP_ID} \
    --db-subnet-group-name ${AURORA_SUBNET_GROUP_NAME}

# For now only two AZs in each region are supported due to the two subnets created above
AZS=("${AWS_REGION}a" "${AWS_REGION}b" "${AWS_REGION}c")

for i in $( seq ${AURORA_INSTANCES} ); do
  aws rds create-db-instance \
    --no-auto-minor-version-upgrade \
    --db-cluster-identifier ${AURORA_CLUSTER} \
    --db-instance-identifier "${AURORA_CLUSTER}-instance-${i}" \
    --db-instance-class ${AURORA_INSTANCE_CLASS} \
    --engine ${AURORA_ENGINE} \
    --enable-performance-insights \
    --performance-insights-retention-period 7 \
    --availability-zone "${AZS[$(((i - 1) % ${#AZS[@]}))]}"
done

for i in $( seq ${AURORA_INSTANCES} ); do
  aws rds wait db-instance-available --db-instance-identifier "${AURORA_CLUSTER}-instance-${i}"
done
