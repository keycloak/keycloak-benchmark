#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

WORKSPACE=$1
echo ${WORKSPACE}
tofu init
tofu workspace select ${WORKSPACE}
tofu state pull
INPUTS=$(tofu output | sed -n 's/input_//p' | sed 's/ //g' | sed 's/^/-var /' | tr -d '"')
tofu destroy -auto-approve ${INPUTS} -lock-timeout=15m
tofu state list
tofu workspace select default
tofu workspace delete ${WORKSPACE}
