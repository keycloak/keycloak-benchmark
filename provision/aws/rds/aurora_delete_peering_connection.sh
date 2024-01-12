#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

ROSA_CLUSTER=$(rosa describe cluster -c ${CLUSTER_NAME} -o json)
ROSA_MACHINE_CIDR=$(echo ${ROSA_CLUSTER} | jq -r .network.machine_cidr)
export AWS_REGION=$(echo ${ROSA_CLUSTER} | jq -r .region.id)

if [ -z "${ROSA_VPC}" ]; then
  bash ${SCRIPT_DIR}/../rosa_oc_login.sh

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
PEERING_CONNECTIONS=$(aws ec2 describe-vpc-peering-connections --output json \
  --filter "Name=requester-vpc-info.vpc-id,Values=${ROSA_VPC}" "Name=status-code,Values=active" "Name=tag-key,Values=AuroraCluster"
)

# If an Aurora cluster is not explicitly provided, remove all Aurora peering connections
if [ -z "${AURORA_CLUSTER}" ]; then
  AURORA_CLUSTERS=$(echo ${PEERING_CONNECTIONS} | jq -r '.VpcPeeringConnections[].Tags[] | select(.Key == "AuroraCluster").Value')
  PEERING_CONNECTION_IDS=$(echo ${PEERING_CONNECTIONS} | jq -r .VpcPeeringConnections[].VpcPeeringConnectionId)
else
  AURORA_CLUSTERS=${AURORA_CLUSTER}
  PEERING_CONNECTION_IDS=$(echo ${PEERING_CONNECTIONS} | jq -r ".VpcPeeringConnections[] | select(.Tags[] | .Key == \"AuroraCluster\" and .Value == \"${AURORA_CLUSTER}\").VpcPeeringConnectionId")
fi

for PEERING_CONNECTION_ID in ${PEERING_CONNECTION_IDS}; do
  aws ec2 delete-vpc-peering-connection --vpc-peering-connection-id ${PEERING_CONNECTION_ID}
done

# Remove the Aurora route from the ROSA cluster
ROSA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
  --filters "Name=vpc-id,Values=${ROSA_VPC}" "Name=association.main,Values=true" \
  --query "RouteTables[*].RouteTableId" \
  --output text
)

for AURORA_CLUSTER in ${AURORA_CLUSTERS}; do

  # Workaround: Assume that the AURORA_REGION is equal to the region of the ROSA cluster
  export AURORA_REGION=${AURORA_REGION:-${AWS_REGION}}

  AURORA_VPC=$(aws ec2 describe-vpcs \
    --filters "Name=tag:AuroraCluster,Values=${AURORA_CLUSTER}" \
    --query 'Vpcs[0]' \
    --output json \
    --region ${AURORA_REGION}
  )
  AURORA_VPC_ID=$(echo ${AURORA_VPC} | jq -r .VpcId)
  AURORA_VPC_CIDR=$(echo ${AURORA_VPC} | jq -r .CidrBlock)

  if [ -n "${ROSA_PUBLIC_ROUTE_TABLE_ID}" ]; then
    aws ec2 delete-route \
      --route-table-id ${ROSA_PUBLIC_ROUTE_TABLE_ID} \
      --destination-cidr-block ${AURORA_VPC_CIDR} || true
  fi

  # Cleanup Aurora Region
  # Remove the ROSA route from the Aurora cluster
  AURORA_PUBLIC_ROUTE_TABLE_ID=$(aws ec2 describe-route-tables \
    --filters "Name=vpc-id,Values=${AURORA_VPC_ID}" "Name=association.main,Values=true" \
    --query "RouteTables[*].RouteTableId" \
    --region ${AURORA_REGION} \
    --output text
  )

  if [ -n "${AURORA_PUBLIC_ROUTE_TABLE_ID}" ]; then
    aws ec2 delete-route \
      --route-table-id ${AURORA_PUBLIC_ROUTE_TABLE_ID} \
      --destination-cidr-block ${ROSA_MACHINE_CIDR} \
      --region ${AURORA_REGION} \
      || true
  fi

  export AURORA_SECURITY_GROUP_NAME=${AURORA_CLUSTER}-security-group
  AURORA_SECURITY_GROUP_ID=$(aws ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=${AURORA_VPC_ID}" "Name=group-name,Values=${AURORA_SECURITY_GROUP_NAME}" \
    --query "SecurityGroups[*].GroupId" \
    --region ${AURORA_REGION} \
    --output text
  )
  if [ -n "${AURORA_SECURITY_GROUP_ID}" ]; then
    aws ec2 revoke-security-group-ingress \
      --group-id ${AURORA_SECURITY_GROUP_ID} \
      --protocol tcp \
      --port 5432 \
      --cidr ${ROSA_MACHINE_CIDR} \
      --region ${AURORA_REGION} \
     || true
  fi
done
