#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
ROSA_CLUSTER=$(rosa describe cluster -c ${CLUSTER_NAME} -o json)
source ${SCRIPT_DIR}/aurora_common.sh

if [ "${SKIP_ROSA_LOGIN}" != "true" ]; then
  bash ${SCRIPT_DIR}/../rosa_oc_login.sh
fi

ROSA_MACHINE_CIDR=$(echo ${ROSA_CLUSTER} | jq -r .network.machine_cidr)
export AWS_REGION=$(echo ${ROSA_CLUSTER} | jq -r .region.id)

AURORA_VPC=$(aws ec2 describe-vpcs \
  --filters "Name=tag:AuroraCluster,Values=${AURORA_CLUSTER}" \
  --query 'Vpcs[0]' \
  --output json \
  --region ${AURORA_REGION} \
)
AURORA_VPC_ID=$(echo ${AURORA_VPC} | jq -r .VpcId)
AURORA_VPC_CIDR=$(echo ${AURORA_VPC} | jq -r .CidrBlock)

NODE=$(oc get nodes --selector=node-role.kubernetes.io/worker \
  -o jsonpath='{.items[0].metadata.name}'
)

ROSA_VPC=$(aws ec2 describe-instances \
  --filters "Name=private-dns-name,Values=${NODE}" \
  --query 'Reservations[*].Instances[*].{VpcId:VpcId}' \
  --output json \
  | jq -r '.[0][0].VpcId'
)

# Create and Accept a VPC Peering Connection between Aurora and a ROSA cluster's VPC if it doesn't already exist
PEERING_CONNECTION_ID=$(aws ec2 describe-vpc-peering-connections \
  --filter "Name=requester-vpc-info.vpc-id,Values=${ROSA_VPC}" "Name=requester-vpc-info.vpc-id,Values=${AURORA_VPC_ID}" "Name=status-code,Values=active"\
  --query "VpcPeeringConnections[*].VpcPeeringConnectionId" \
  --output text
)

if [ -z "${PEERING_CONNECTION_ID}" ]; then
  PEERING_CONNECTION_ID=$(aws ec2 create-vpc-peering-connection \
    --vpc-id ${ROSA_VPC} \
    --peer-vpc-id ${AURORA_VPC_ID} \
    --peer-region ${AURORA_REGION} \
    --tag-specifications "ResourceType=vpc-peering-connection, Tags=[{Key=AuroraCluster,Value=${AURORA_CLUSTER}}]" \
    --query VpcPeeringConnection.VpcPeeringConnectionId \
    --output text
  )
fi

aws ec2 wait vpc-peering-connection-exists --vpc-peering-connection-ids ${PEERING_CONNECTION_ID}
aws ec2 wait vpc-peering-connection-exists --vpc-peering-connection-ids ${PEERING_CONNECTION_ID} --region ${AURORA_REGION}

# Accept the peering connection in the Aurora region
aws ec2 accept-vpc-peering-connection \
  --vpc-peering-connection-id ${PEERING_CONNECTION_ID} \
  --region ${AURORA_REGION}

# Update the ROSA Cluster VPC's Route Table
ROSA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
  --filters "Name=vpc-id,Values=${ROSA_VPC}" "Name=association.main,Values=true" \
  --query "RouteTables[*].RouteTableId" \
  --output text
)


EXISTS=$(aws ec2 describe-route-tables\
  --filters "Name=route-table-id,Values=${ROSA_PUBLIC_ROUTE_TABLE_ID}" \
    "Name=route.destination-cidr-block,Values=${AURORA_VPC_CIDR}" \
    "Name=route.vpc-peering-connection-id,Values=${PEERING_CONNECTION_ID}" \
  --query "RouteTables[*].RouteTableId" \
  --output text)

if [ "${EXISTS}" == "" ]; then
  aws ec2 create-route \
    --route-table-id ${ROSA_PUBLIC_ROUTE_TABLE_ID} \
    --destination-cidr-block ${AURORA_VPC_CIDR} \
    --vpc-peering-connection-id ${PEERING_CONNECTION_ID}
else
  echo "Route to ${AURORA_VPC_CIDR} already exists for region ${AWS_REGION}."
fi

# Update the Aurora Cluster VPC's Route Table
AURORA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
  --filters "Name=vpc-id,Values=${AURORA_VPC_ID}" "Name=association.main,Values=true" \
  --query "RouteTables[*].RouteTableId" \
  --region ${AURORA_REGION} \
  --output text
)

EXISTS=$(aws ec2 describe-route-tables\
  --filters "Name=route-table-id,Values=${AURORA_PUBLIC_ROUTE_TABLE_ID}" \
    "Name=route.destination-cidr-block,Values=${ROSA_MACHINE_CIDR}" \
    "Name=route.vpc-peering-connection-id,Values=${PEERING_CONNECTION_ID}" \
  --query "RouteTables[*].RouteTableId" \
  --region ${AURORA_REGION} \
  --output text)

if [ "${EXISTS}" == "" ]; then
  aws ec2 create-route \
    --route-table-id ${AURORA_PUBLIC_ROUTE_TABLE_ID} \
    --destination-cidr-block ${ROSA_MACHINE_CIDR} \
    --vpc-peering-connection-id ${PEERING_CONNECTION_ID} \
    --region ${AURORA_REGION}
else
  echo "Route to ${ROSA_MACHINE_CIDR} already exists for region ${AURORA_REGION}."
fi

# Update the RDS Instance's Security Group
AURORA_SECURITY_GROUP_ID=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=${AURORA_SECURITY_GROUP_NAME}" \
  --query "SecurityGroups[*].GroupId" \
  --region ${AURORA_REGION} \
  --output text
)

EXISTS=$(aws ec2 describe-security-groups \
  --filters  \
    "Name=group-id,Values=${AURORA_SECURITY_GROUP_ID}" \
    "Name=ip-permission.protocol,Values=tcp" \
    "Name=ip-permission.from-port,Values=5432" \
    "Name=ip-permission.cidr,Values=${ROSA_MACHINE_CIDR}" \
  --query "SecurityGroups[*].GroupId" \
  --region ${AURORA_REGION} \
  --output text)

if [ "${EXISTS}" == "" ]; then
  aws ec2 authorize-security-group-ingress \
    --group-id ${AURORA_SECURITY_GROUP_ID} \
    --protocol tcp \
    --port 5432 \
    --cidr ${ROSA_MACHINE_CIDR} \
    --region ${AURORA_REGION}
else
  echo "Ingress authorization already exists from ${ROSA_MACHINE_CIDR} for region ${AURORA_REGION}"
fi
