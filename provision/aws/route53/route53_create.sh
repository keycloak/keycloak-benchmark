#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/route53_common.sh

function createHealthCheck() {
  # Creating a hash of the caller reference to allow for names longer than 64 characters
  # shellcheck disable=SC2207
  REF=($(echo $1 | sha1sum ))
  # shellcheck disable=SC2128
  aws route53 create-health-check \
  --caller-reference "$REF" \
  --query "HealthCheck.Id" \
  --output text \
  --health-check-config '
  {
    "Type": "HTTPS",
    "ResourcePath": "/lb-check",
    "FullyQualifiedDomainName": "'$1'",
    "Port": 443,
    "RequestInterval": 30,
    "FailureThreshold": 1,
    "EnableSNI": true
  }
  '
}

function rosa_vpc() {
  export CLUSTER_NAME=$1
  AWS_REGION=$2

  bash ${SCRIPT_DIR}/../rosa_oc_login.sh > /dev/null

  NODE=$(oc get nodes --selector=node-role.kubernetes.io/worker \
  -o jsonpath='{.items[0].metadata.name}'
  )

  aws ec2 describe-instances \
    --filters "Name=private-dns-name,Values=${NODE}" \
    --query 'Reservations[*].Instances[*].{VpcId:VpcId}' \
    --output json \
    --region ${AWS_REGION} \
    | jq -r '.[0][0].VpcId'
}

PRIMARY_CLUSTER=${PRIMARY_CLUSTER:-"gh-keycloak"}
BACKUP_CLUSTER=${BACKUP_CLUSTER:-${PRIMARY_CLUSTER}}

PRIMARY_CLUSTER_REGION=$(rosa describe cluster -c ${PRIMARY_CLUSTER} -o json | jq -r .region.id)
BACKUP_CLUSTER_REGION=$(rosa describe cluster -c ${BACKUP_CLUSTER} -o json | jq -r .region.id)

# Retrieve ROSA cluster VPCs
PRIMARY_CLUSTER_VPC=$(rosa_vpc ${PRIMARY_CLUSTER} ${PRIMARY_CLUSTER_REGION})
BACKUP_CLUSTER_VPC=$(rosa_vpc ${BACKUP_CLUSTER} ${BACKUP_CLUSTER_REGION})

# Retrieve Load Balancer for ROSA Clusters
PRIMARY_LB=$(aws elb describe-load-balancers \
  --query "LoadBalancerDescriptions[?VPCId=='${PRIMARY_CLUSTER_VPC}']" \
  --region ${PRIMARY_CLUSTER_REGION} \
  --output json
)
PRIMARY_LB_DNS=$(echo ${PRIMARY_LB} | jq -r ".[0].DNSName")
PRIMARY_LB_HOSTED_ZONE_ID=$(echo ${PRIMARY_LB} | jq -r ".[0].CanonicalHostedZoneNameID")

BACKUP_LB=$(aws elb describe-load-balancers \
  --query "LoadBalancerDescriptions[?VPCId=='${BACKUP_CLUSTER_VPC}']" \
  --region ${BACKUP_CLUSTER_REGION} \
  --output json
)
BACKUP_LB_DNS=$(echo ${BACKUP_LB} | jq -r ".[0].DNSName")
BACKUP_LB_HOSTED_ZONE_ID=$(echo ${BACKUP_LB} | jq -r ".[0].CanonicalHostedZoneNameID")

# Retrieve the Hosted Zone associated with our root domain
HOSTED_ZONE_ID=$(aws route53 list-hosted-zones \
  --query "HostedZones[?Name=='${ROOT_DOMAIN}.'].Id" \
  --output text \
  | sed 's:.*/::'
)

SUBDOMAIN=${PRIMARY_CLUSTER}-${BACKUP_CLUSTER}-$(echo $RANDOM | base64 | tr -dc  '[:alnum:]' | tr '[:upper:]' '[:lower:]')
DOMAIN="${SUBDOMAIN}.${ROOT_DOMAIN}"
CLIENT_DOMAIN="client.${DOMAIN}"
PRIMARY_DOMAIN="primary.${DOMAIN}"
BACKUP_DOMAIN="backup.${DOMAIN}"

# Create the health checks for the primary and backup specific endpoints
PRIMARY_HEALTH_ID=$(createHealthCheck ${PRIMARY_DOMAIN})
BACKUP_HEALTH_ID=$(createHealthCheck ${BACKUP_DOMAIN})

# Create Failover records in the domain's Hosted Zone
CHANGE_ID=$(aws route53 change-resource-record-sets \
  --hosted-zone-id ${HOSTED_ZONE_ID} \
  --query "ChangeInfo.Id" \
  --output text \
  --change-batch '
  {
    "Comment": "Creating Record Set for '${DOMAIN}'",
  	"Changes": [{
  		"Action": "CREATE",
  		"ResourceRecordSet": {
  			"Name": "'${PRIMARY_DOMAIN}'",
  			"Type": "A",
        "AliasTarget": {
          "HostedZoneId": "'${PRIMARY_LB_HOSTED_ZONE_ID}'",
          "DNSName": "'${PRIMARY_LB_DNS}'",
          "EvaluateTargetHealth": true
        }
  		}
  	}, {
  		"Action": "CREATE",
  		"ResourceRecordSet": {
  			"Name": "'${BACKUP_DOMAIN}'",
  			"Type": "A",
        "AliasTarget": {
          "HostedZoneId": "'${BACKUP_LB_HOSTED_ZONE_ID}'",
          "DNSName": "'${BACKUP_LB_DNS}'",
          "EvaluateTargetHealth": true
        }
  		}
  	}, {
  		"Action": "CREATE",
  		"ResourceRecordSet": {
  			"Name": "'${CLIENT_DOMAIN}'",
  			"Type": "A",
        "SetIdentifier": "client-failover-primary-'${SUBDOMAIN}'",
        "Failover": "PRIMARY",
        "HealthCheckId": "'${PRIMARY_HEALTH_ID}'",
        "AliasTarget": {
          "HostedZoneId": "'${HOSTED_ZONE_ID}'",
          "DNSName": "'${PRIMARY_DOMAIN}'",
          "EvaluateTargetHealth": true
        }
  		}
  	}, {
  		"Action": "CREATE",
  		"ResourceRecordSet": {
  			"Name": "'${CLIENT_DOMAIN}'",
  			"Type": "A",
        "SetIdentifier": "client-failover-backup-'${SUBDOMAIN}'",
        "Failover": "SECONDARY",
        "HealthCheckId": "'${BACKUP_HEALTH_ID}'",
        "AliasTarget": {
          "HostedZoneId": "'${HOSTED_ZONE_ID}'",
          "DNSName": "'${BACKUP_DOMAIN}'",
          "EvaluateTargetHealth": true
        }
  		}
  	}]
  }
  '
)

aws route53 wait resource-record-sets-changed --id ${CHANGE_ID}

echo "Domain: ${DOMAIN}"
echo "Client Site URL: ${CLIENT_DOMAIN}"
echo "Primary Site URL: ${PRIMARY_DOMAIN}"
echo "Backup Site URL: ${BACKUP_DOMAIN}"
