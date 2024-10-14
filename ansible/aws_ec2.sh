#!/usr/bin/env bash
set -e
cd $(dirname "${BASH_SOURCE[0]}")

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

OPERATION=$1
REGION=$2

case $OPERATION in
  requirements)
    ansible-galaxy collection install -r requirements.yml
    pip3 install boto3 botocore
  ;;
  create|delete|start|stop)
    if [ -f "env.yml" ]; then ANSIBLE_CUSTOM_VARS_ARG="-e @env.yml"; fi
    ansible-playbook aws_ec2.yml -v -e "region=$REGION" -e "operation=$OPERATION" $ANSIBLE_CUSTOM_VARS_ARG "${@:3}"
  ;;
  *)
    echo "Invalid option!"
    echo "Available operations: requirements, create, delete, start, stop."
  ;;
esac
