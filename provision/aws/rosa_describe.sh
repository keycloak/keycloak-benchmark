#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne "1" ]]; then
  echo "This describes one ROSA cluster in JSON, but only information that is not security related."
  echo "Provide exactly one parameter with the name of the ROSA cluster"
  exit 1
fi
rosa describe cluster -c $1 -o json | jq '. | { rosa: { creation_timestamp: .creation_timestamp, flavour: .flavour, multi_az: .multi_az, nodes: .nodes, openshift_version: .openshift_version, region: .region, version: .version }} '
