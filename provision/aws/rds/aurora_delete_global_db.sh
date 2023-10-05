#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function isGlobalClusterMember() {
    MEMBER=$(aws rds describe-global-clusters \
      --query "GlobalClusters[?GlobalClusterIdentifier=='$1'].GlobalClusterMembers[*]" \
      | jq -r ".[][] | select(.DBClusterArn==\"$2\")"
    )
    [[ -n ${MEMBER} ]]
}

if [ -z "${AURORA_GLOBAL_CLUSTER}" ]; then
  echo "AURORA_GLOBAL_CLUSTER variable must be set"
  exit 1
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_global_common.sh

export AWS_PAGER=""
AURORA_GLOBAL_REGIONS=$(globalClusterRegions ${AURORA_GLOBAL_CLUSTER})
GLOBAL_REGIONS=(${AURORA_GLOBAL_REGIONS})
PRIMARY_REGION=${GLOBAL_REGIONS[0]}

export AURORA_GLOBAL_CLUSTER_IDENTIFIER="--global-cluster-identifier ${AURORA_GLOBAL_CLUSTER}"
# We must iterate over the regions in reverse order as the Primary cluster must be deleted last
for (( i = ${#GLOBAL_REGIONS[@]} - 1 ; i >= 0 ; i-- )) ; do
  REGION=${GLOBAL_REGIONS[i]}
  export AURORA_CLUSTER=${AURORA_GLOBAL_CLUSTER}-${REGION}
  export AURORA_REGION=${REGION}
  export AURORA_VPC_CIDR=$(globalAuroraVpcCidr $i)

  AURORA_CLUSTER_ARN=$(aws rds describe-db-clusters \
    --query "DBClusters[?DBClusterIdentifier=='${AURORA_CLUSTER}'].DBClusterArn" \
    --region ${AURORA_REGION} \
    --output text
  )

  if [ -n "${AURORA_CLUSTER_ARN}" ]; then
    aws rds remove-from-global-cluster \
      --db-cluster-identifier ${AURORA_CLUSTER_ARN} \
      --global-cluster-identifier ${AURORA_GLOBAL_CLUSTER} \
      --region ${AURORA_REGION} \
      || true

    # Wait for Region cluster to be removed from Global Cluster
    count=0
    until ! isGlobalClusterMember ${AURORA_GLOBAL_CLUSTER} ${AURORA_CLUSTER_ARN} || (( count++ >= 60 )); do
      sleep 10
    done
  fi

  ${SCRIPT_DIR}/aurora_delete.sh || true
done

# Delete Route53 entry for the cluster
export SUBDOMAIN="${AURORA_GLOBAL_CLUSTER}.aurora-global"
${SCRIPT_DIR}/../route53/route53_delete.sh

# Remove Roles and Policies created for Lambda
POLICY_ARN=$(aws iam list-policies \
  --scope Local \
  --query "Policies[?PolicyName=='${AURORA_GLOBAL_CLUSTER}'].Arn" \
  --output text
)
if [ -n "${POLICY_ARN}" ]; then
  aws iam detach-role-policy \
    --role-name ${AURORA_GLOBAL_CLUSTER} \
    --policy-arn ${POLICY_ARN}

  aws iam detach-role-policy \
    --role-name ${AURORA_GLOBAL_CLUSTER} \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

  aws iam delete-policy --policy-arn ${POLICY_ARN} --region ${PRIMARY_REGION}
  aws iam delete-role --role-name ${AURORA_GLOBAL_CLUSTER} --region ${PRIMARY_REGION}
fi
# Remove Lambda and event rules
for REGION in ${AURORA_GLOBAL_REGIONS}; do
  export AWS_REGION=${REGION}
  aws events remove-targets --rule ${AURORA_GLOBAL_CLUSTER} --ids 1 || true
  aws events delete-rule --name ${AURORA_GLOBAL_CLUSTER} || true
  aws lambda delete-function --function-name ${AURORA_GLOBAL_CLUSTER}-failover || true
done

aws rds delete-global-cluster --region ${PRIMARY_REGION}  \
  --global-cluster-identifier ${AURORA_GLOBAL_CLUSTER}
