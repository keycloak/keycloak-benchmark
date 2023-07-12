#!/bin/bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh

export AWS_REGION=$(rosa describe cluster -c ${CLUSTER_NAME} -o json | jq -r .region.id)

if [ -z "${ROSA_VPC}" ]; then
  sh ${SCRIPT_DIR}/../rosa_oc_login.sh

  NODE=$(oc get nodes --selector=node-role.kubernetes.io/worker \
  -o jsonpath='{.items[0].metadata.name}'
  )

  ROSA_VPC=$(aws ec2 describe-instances \
    --filters "Name=private-dns-name,Values=$NODE" \
    --query 'Reservations[*].Instances[*].{VpcId:VpcId}' \
    --output json \
    | jq -r '.[0][0].VpcId'
  )
fi

# Delete all Peering connections
PEERING_CONNECTION_IDS=$(aws ec2 describe-vpc-peering-connections \
  --filter "Name=requester-vpc-info.vpc-id,Values=${ROSA_VPC}" "Name=status-code,Values=active"\
  --query "VpcPeeringConnections[*].VpcPeeringConnectionId" \
  --output text
)

for PEERING_CONNECTION_ID in ${PEERING_CONNECTION_IDS}; do
  aws ec2 delete-vpc-peering-connection --vpc-peering-connection-id ${PEERING_CONNECTION_ID}
done

# Remove the Aurora route from the ROSA cluster
ROSA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
  --filters "Name=vpc-id,Values=${ROSA_VPC}" "Name=association.main,Values=true" \
  --query "RouteTables[*].RouteTableId" \
  --output text
)

if [ -n "${ROSA_PUBLIC_ROUTE_TABLE_ID}" ]; then
  aws ec2 delete-route \
    --route-table-id ${ROSA_PUBLIC_ROUTE_TABLE_ID} \
    --destination-cidr-block ${AURORA_VPC_CIDR} || true
fi

# Cleanup Aurora Region
if [ -n ${AURORA_REGION} ]; then
  AURORA_VPC=$(aws ec2 describe-vpcs \
    --filters "Name=cidr,Values=${AURORA_VPC_CIDR}" "Name=tag:AuroraCluster,Values=${AURORA_CLUSTER}" \
    --query 'Vpcs[*].VpcId' \
    --region ${AURORA_REGION} \
    --output text
  )

  # Remove the ROSA route from the Aurora cluster
  AURORA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
    --filters "Name=vpc-id,Values=${AURORA_VPC}" "Name=association.main,Values=true" \
    --query "RouteTables[*].RouteTableId" \
    --region ${AURORA_REGION} \
    --output text
  )

  if [ -n "${AURORA_PUBLIC_ROUTE_TABLE_ID}" ]; then
    aws ec2 delete-route \
      --route-table-id ${AURORA_PUBLIC_ROUTE_TABLE_ID} \
      --destination-cidr-block 10.0.0.0/16 \
      --region ${AURORA_REGION} \
      || true
  fi

  AURORA_SECURITY_GROUP_ID=$(aws ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=${AURORA_VPC}" "Name=group-name,Values=${AURORA_SECURITY_GROUP_NAME}" \
    --query "SecurityGroups[*].GroupId" \
    --region ${AURORA_REGION} \
    --output text
  )
  if [ -n "${AURORA_SECURITY_GROUP_ID}" ]; then
    aws ec2 revoke-security-group-ingress \
      --group-id ${AURORA_SECURITY_GROUP_ID} \
      --protocol tcp \
      --port 5432 \
      --cidr 10.0.0.0/16 \
      --region ${AURORA_REGION}
  fi
fi
