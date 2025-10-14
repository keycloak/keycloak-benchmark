#!/usr/bin/env bash
set -e
cd $(dirname "${BASH_SOURCE[0]}")

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function usage {
  echo "Usage:"
  echo "A) $0 requirements                        Install Python requirements."
  echo "B) $0 create|delete|start|stop <region>   Create/delete/start/stop benchmark EC2 instances in AWS region."
  exit 1
}

OPERATION=$1
REGION=$2

CLUSTER_ID=${CLUSTER_ID:-$USER}

case $OPERATION in
  requirements)
    ansible-galaxy collection install -r requirements.yml
    pip3 install boto3 botocore
  ;;
  create|delete|start|stop)
    if [ -z "$REGION" ]; then echo "Region is not set."; usage; fi
    if [ -f "env.yml" ]; then ANSIBLE_CUSTOM_VARS_ARG="-e @env.yml"; fi
    ansible-playbook aws_ec2.yml -v -e "region=$REGION" -e "operation=$OPERATION" -e "cluster_identifier=${CLUSTER_ID}" $ANSIBLE_CUSTOM_VARS_ARG "${@:3}"
  ;;
  *)
    echo "Operation '$OPERATION' is not supported."
    usage
  ;;
esac
