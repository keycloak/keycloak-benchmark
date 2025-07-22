#!/bin/bash

set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

usage() {
  echo "Usage: $0 <region> <availability zones>"
  echo "Example: $0 \"eu-west-1\" \"a,b,c\""
  echo "This will output: eu-west-1a,eu-west-1b,eu-west-1c"
  exit 1
}

if [ "$#" -ne 2 ]; then
  usage
fi

# parse the availability zones, splitting by comma
IFS=, read -r -a AZS_ARRAY <<< "$2"
# prefix the region name
AZS_ARRAY_WITH_REGION=("${AZS_ARRAY[@]/#/$1}")
# print the array using comma as separator
IFS=,
echo "${AZS_ARRAY_WITH_REGION[*]}"
