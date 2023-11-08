#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/accelerator_common.sh

function waitForHostname() {
  SVC_NAME=$1
  NAMESPACE=$2

  IP=""
  count=0
  while [[ -z $IP || count++ -gt 120 ]]; do
    IP=$(oc -n ${NAMESPACE} get svc ${SVC_NAME} --template="{{range .status.loadBalancer.ingress}}{{.hostname}}{{end}}")
    sleep 1
  done
  if [ $count -gt 120 ]; then
    echo "Timeout waiting for accelerator ${ACCELERATOR_ARN} to be removed"
    exit 1
  fi
  echo $IP
}

function createLoadBalancer() {
  export CLUSTER_NAME=$1
  REGION=$2
  SVC_NAME=$3
  NAMESPACE=$4

  bash ${SCRIPT_DIR}/../rosa_oc_login.sh > /dev/null
  oc create namespace ${NAMESPACE}  > /dev/null || true
  cat <<EOF | oc apply -n ${NAMESPACE} -f - > /dev/null
  apiVersion: v1
  kind: Service
  metadata:
    name: ${SVC_NAME}
    annotations:
      service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
      service.beta.kubernetes.io/aws-load-balancer-healthcheck-path: "/health/live"
      service.beta.kubernetes.io/aws-load-balancer-healthcheck-protocol: "https"
      service.beta.kubernetes.io/aws-load-balancer-healthcheck-interval: "10"
      service.beta.kubernetes.io/aws-load-balancer-healthcheck-healthy-threshold: "3"
      service.beta.kubernetes.io/aws-load-balancer-healthcheck-unhealthy-threshold: "3"
  spec:
    ports:
    - name: https
      port: 443
      protocol: TCP
      targetPort: 8443
    selector:
      app: keycloak
      app.kubernetes.io/instance: keycloak
      app.kubernetes.io/managed-by: keycloak-operator
    sessionAffinity: None
    type: LoadBalancer
EOF
  LB_DNS=$(waitForHostname ${SVC_NAME} ${NAMESPACE})
  LB_ARN=$(aws elbv2 describe-load-balancers \
    --query "LoadBalancers[?DNSName=='${LB_DNS}'].LoadBalancerArn" \
    --region ${REGION} \
    --output text
  )
  echo ${LB_ARN}
}

requiredEnv ACCELERATOR_NAME PRIMARY_CLUSTER BACKUP_CLUSTER KEYCLOAK_NAMESPACE

PRIMARY_CLUSTER_REGION=$(rosa describe cluster -c ${PRIMARY_CLUSTER} -o json | jq -r .region.id)
BACKUP_CLUSTER_REGION=$(rosa describe cluster -c ${BACKUP_CLUSTER} -o json | jq -r .region.id)

if [[ "${PRIMARY_CLUSTER_REGION}" != "${BACKUP_CLUSTER_REGION}" ]]; then
  echo "ROSA Clusters '${PRIMARY_CLUSTER}' and '${BACKUP_CLUSTER}' must be deployed in the same AWS region for a Multi-AZ deployment"
  exit 1
fi

ENDPOINT_GROUP_REGION=${PRIMARY_CLUSTER_REGION}

PRIMARY_ENDPOINT_ARN=$(createLoadBalancer ${PRIMARY_CLUSTER} ${PRIMARY_CLUSTER_REGION} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE})
BACKUP_ENDPOINT_ARN=$(createLoadBalancer ${BACKUP_CLUSTER} ${BACKUP_CLUSTER_REGION} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE})

ACCELERATOR=$(aws globalaccelerator create-accelerator \
  --name ${ACCELERATOR_NAME} \
  --query 'Accelerator' \
  --ip-address-type DUAL_STACK \
  --output json
)

ACCELERATOR_ARN=$(echo ${ACCELERATOR} | jq -r .AcceleratorArn)
ACCELERATOR_DNS=$(echo ${ACCELERATOR} | jq -r .DnsName)
ACCELERATOR_DUAL_STACK_DNS=$(echo ${ACCELERATOR} | jq -r .DualStackDnsName)

LISTENER_ARN=$(aws globalaccelerator create-listener \
  --accelerator-arn ${ACCELERATOR_ARN} \
  --port-ranges '[{"FromPort":443,"ToPort":443}]' \
  --protocol TCP \
  --query 'Listener.ListenerArn' \
  --output text
)

ENDPOINTS=$(echo '
[
  {
    "EndpointId": "'${PRIMARY_ENDPOINT_ARN}'",
    "Weight": 100,
    "ClientIPPreservationEnabled": false
  },
  {
    "EndpointId": "'${BACKUP_ENDPOINT_ARN}'",
    "Weight": 0,
    "ClientIPPreservationEnabled": false
  }
]' | jq -c .
)

ENDPOINT_GROUP_ARN=$(aws globalaccelerator create-endpoint-group \
  --listener-arn ${LISTENER_ARN} \
  --traffic-dial-percentage 100 \
  --endpoint-configurations ${ENDPOINTS} \
  --endpoint-group-region ${ENDPOINT_GROUP_REGION}
)

echo "ACCELERATOR DNS: ${ACCELERATOR_DNS}"
echo "ACCELERATOR DUAL_STACK DNS: ${ACCELERATOR_DNS}"
