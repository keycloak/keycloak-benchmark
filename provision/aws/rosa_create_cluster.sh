#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/rosa_common.sh

AWS_ACCOUNT=${AWS_ACCOUNT:-$(aws sts get-caller-identity --query "Account" --output text)}

AVAILABILITY_ZONES=${AVAILABILITY_ZONES:-"${REGION}a"}

# Count the AZs
IFS=, read -r -a AZS_ARRAY <<< "${AVAILABILITY_ZONES}"
AVAILABILITY_ZONES_COUNT="${#AZS_ARRAY[@]}"
echo "AVAILABILITY_ZONES_COUNT=$AVAILABILITY_ZONES_COUNT"

requiredEnv AWS_ACCOUNT CLUSTER_NAME REGION CIDR

export CLUSTER_NAME=${CLUSTER_NAME:-$(whoami)}

echo "Checking if cluster ${CLUSTER_NAME} already exists."
if rosa describe cluster --cluster="${CLUSTER_NAME}"; then
  echo "Cluster ${CLUSTER_NAME} already exists."
else
  if [ -z "$CLUSTER_ADMIN_PASSWORD" ]; then echo "CLUSTER_ADMIN_PASSWORD needs to be set."; exit 1; fi
  echo "Verifying ROSA prerequisites."
  echo "Check if AWS CLI is installed."; aws --version
  echo "Check if ROSA CLI is installed."; rosa version
  echo "Check if ELB service role is enabled."
  if ! aws iam get-role --role-name "AWSServiceRoleForElasticLoadBalancing" --no-cli-pager; then
    aws iam create-service-linked-role --aws-service-name "elasticloadbalancing.amazonaws.com"
  fi
  rosa whoami
  rosa verify quota

  echo "Installing ROSA cluster ${CLUSTER_NAME}"

  cd ${SCRIPT_DIR}/../opentofu/modules/rosa-hcp
  WORKSPACE=${CLUSTER_NAME}-${REGION}

  TOFU_CMD="tofu apply -auto-approve \
    -var vpc_cidr=${CIDR} \
    -var availability_zones=${AVAILABILITY_ZONES} \
    -var cluster_name=${CLUSTER_NAME} \
    -var cluster_admin_password=${CLUSTER_ADMIN_PASSWORD} \
    -var region=${REGION}"

  if [ -n "${COMPUTE_MACHINE_TYPE}" ]; then
    TOFU_CMD+=" -var instance_type=${COMPUTE_MACHINE_TYPE}"
  fi

  if [ -n "${VERSION}" ]; then
    TOFU_CMD+=" -var openshift_version=${VERSION}"
  fi

  if [ -n "${REPLICAS}" ]; then
    TOFU_CMD+=" -var replicas=${REPLICAS}"
  fi

  bash ${SCRIPT_DIR}/../opentofu/create.sh ${WORKSPACE} "${TOFU_CMD}"
fi

SCALING_MACHINE_POOL=$(rosa list machinepools -c "${CLUSTER_NAME}" -o json | jq -r '.[] | select(.id == "scaling") | .id')
if [[ "${SCALING_MACHINE_POOL}" != "scaling" ]] && [[ "${AVAILABILITY_ZONES_COUNT}" == "1" ]]; then
    rosa create machinepool -c "${CLUSTER_NAME}" --instance-type "${COMPUTE_MACHINE_TYPE:-c7g.2xlarge}" --max-replicas 15 --min-replicas 1 --name scaling --enable-autoscaling --autorepair
fi

cd ${SCRIPT_DIR}
./rosa_oc_login.sh
../infinispan/install_operator.sh

# cryostat operator depends on certmanager operator
./rosa_install_certmanager_operator.sh
./rosa_install_cryotstat_operator.sh

./rosa_install_tempo_operator.sh

echo "Enabling monitoring for user workloads"
oc apply -f ${SCRIPT_DIR}/../openshift/cluster-monitoring-config.yaml
echo "Enabling user alert routing"
oc apply -f ${SCRIPT_DIR}/../openshift/user-workload-monitoring-config.yaml
waitFor openshift-user-workload-monitoring statefulset alertmanager-user-workload
oc -n openshift-user-workload-monitoring rollout status --watch --timeout=2m statefulset.apps/alertmanager-user-workload

echo "Cluster ${CLUSTER_NAME} is ready."
