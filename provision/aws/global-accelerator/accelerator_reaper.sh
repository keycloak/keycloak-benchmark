#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/accelerator_common.sh

ACCELERATORS=$(aws globalaccelerator list-accelerators \
  --query "Accelerators[].Name" \
  --output text
)

export DELETE_LB=false
for ACCELERATOR_NAME in ${ACCELERATORS}; do
  export ACCELERATOR_NAME
  ${SCRIPT_DIR}/accelerator_multi_az_delete.sh
done
