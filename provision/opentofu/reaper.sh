#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

cd $1
tofu init
declare -a WORKSPACES=($(tofu workspace list | sed 's/*//' | grep -v "default"))
for WORKSPACE in ${WORKSPACES}; do
  bash ${SCRIPT_DIR}/destroy.sh ${WORKSPACE}
done
