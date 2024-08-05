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
  SVC_NAME=$2
  NAMESPACE=$3
  ACCELERATOR_NAME=$4

  bash ${SCRIPT_DIR}/../rosa_oc_login.sh > /dev/null
  oc create namespace ${NAMESPACE}  > /dev/null || true
  cat <<EOF | oc apply -n ${NAMESPACE} -f - > /dev/null
  apiVersion: v1
  kind: Service
  metadata:
    name: ${SVC_NAME}
    annotations:
      service.beta.kubernetes.io/aws-load-balancer-additional-resource-tags: accelerator=${ACCELERATOR_NAME},site=${CLUSTER_NAME},namespace=${NAMESPACE}
      service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
      service.beta.kubernetes.io/aws-load-balancer-healthcheck-path: "/lb-check"
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
}

requiredEnv ACCELERATOR_NAME CLUSTER_1 CLUSTER_2 KEYCLOAK_NAMESPACE

CLUSTER_1_REGION=$(rosa describe cluster -c ${CLUSTER_1} -o json | jq -r .region.id)
CLUSTER_2_REGION=$(rosa describe cluster -c ${CLUSTER_2} -o json | jq -r .region.id)

if [[ "${CLUSTER_1_REGION}" != "${CLUSTER_2_REGION}" ]]; then
  echo "ROSA Clusters '${CLUSTER_1}' and '${CLUSTER_2}' must be deployed in the same AWS region for a Multi-AZ deployment"
  exit 1
fi

createLoadBalancer ${CLUSTER_1} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE} ${ACCELERATOR_NAME}
createLoadBalancer ${CLUSTER_2} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE} ${ACCELERATOR_NAME}

TOFU_CMD="tofu apply -auto-approve \
  -var aws_region=${CLUSTER_1_REGION} \
  -var lb_service_name="${KEYCLOAK_NAMESPACE}/${ACCELERATOR_LB_NAME}" \
  -var name=${ACCELERATOR_NAME} \
  -var site_a=${CLUSTER_1} \
  -var site_b=${CLUSTER_2}"

cd ${SCRIPT_DIR}/../../opentofu/modules/aws/accelerator
source ${SCRIPT_DIR}/../../opentofu/create.sh ${ACCELERATOR_NAME} "${TOFU_CMD}"

ACCELERATOR_DNS=$(tofu output -json | jq -r .dns_name.value)
ACCELERATOR_WEBHOOK=$(tofu output -json | jq -r .webhook_url.value)

echo "ACCELERATOR DNS: ${ACCELERATOR_DNS}"
echo "ACCELERATOR WEBHOOK: ${ACCELERATOR_WEBHOOK}"
if [ "${GITHUB_ENV}" != "" ]; then
  echo "ACCELERATOR_DNS=${ACCELERATOR_DNS}" >> ${GITHUB_ENV}
  echo "ACCELERATOR_WEBHOOK=${ACCELERATOR_WEBHOOK}" >> ${GITHUB_ENV}
fi
