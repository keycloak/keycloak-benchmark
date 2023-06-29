#!/bin/bash +x
set -e
cd $(dirname "${BASH_SOURCE[0]}")

OPERATION=$1
REGION=$2

case $OPERATION in
  requirements)
    ansible-galaxy collection install -r requirements.yml
    pip3 install --user boto3 botocore
  ;;
  create|delete|start|stop)
    if [ -f "env.yml" ]; then CUSTOM_VARS_ARG="-e @env.yml"; fi
    ansible-playbook aws_ec2.yml -v -e "region=$REGION" -e "operation=$OPERATION" $CUSTOM_VARS_ARG
  ;;
  *)
    echo "Invalid option!"
    echo "Available operations: requirements, create, delete, start, stop."
  ;;
esac
