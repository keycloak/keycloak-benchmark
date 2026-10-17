#!/usr/bin/env bash

# Reap leaked VPCs created by Aurora integration tests in the main
# keycloak/keycloak repository.
#
# The Keycloak Aurora workflow creates a dedicated VPC and gives it a Name tag
# with this format:
#
#   Aurora Cluster gh-action-<short-commit-sha>-<github-run-id>-<run-attempt>
#
# Normally the workflow removes the Aurora cluster and its networking when it
# finishes. A cancelled or otherwise interrupted workflow can leave the VPC
# behind, however, and the keycloak-benchmark AWS account then accumulates
# resources that are no longer associated with an active test run.
#
# This reaper is called by the benchmark repository's scheduled deletion
# workflow. It finds non-default Aurora VPCs bearing the Keycloak naming
# convention in us-east-1 (or AWS_REGION), extracts the GitHub Actions run ID,
# and asks the GitHub API for that run's current status. VPCs belonging to
# queued or running jobs are preserved. A VPC is eligible for deletion when
# its run is confirmed as completed or GitHub confirms with a 404 that the run
# no longer exists (for example, after the run ages out of Actions retention).
#
# Eligible VPCs are passed to the standard Aurora deletion script so database
# instances and clusters are removed before their subnets, security groups,
# and VPC. Names that do not match the expected format, GitHub API failures
# other than a confirmed 404, and unknown run states are skipped rather than
# risking deletion of resources whose liveness cannot be established.
#
# Set DRY_RUN=true to perform VPC discovery and GitHub status lookups while
# only reporting the VPCs that would be deleted. Dry-run mode never calls the
# AWS DeleteVpc API.
set -uo pipefail

if [[ "${RUNNER_DEBUG:-}" == "1" ]]; then
  set -x
fi

AWS_REGION=${AWS_REGION:-us-east-1}
KEYCLOAK_REPOSITORY=${KEYCLOAK_REPOSITORY:-keycloak/keycloak}
DRY_RUN=${DRY_RUN:-false}
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

if [[ "${DRY_RUN}" != "true" && "${DRY_RUN}" != "false" ]]; then
  echo "::error::DRY_RUN must be either 'true' or 'false', but was '${DRY_RUN}'."
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "::warning::The GitHub CLI is unavailable; skipping Keycloak GitHub Actions VPC cleanup."
  exit 0
fi

VPCS_JSON=$(aws ec2 describe-vpcs \
  --region "${AWS_REGION}" \
  --filters 'Name=is-default,Values=false' 'Name=tag:Name,Values=Aurora Cluster gh-action-*' \
  --query 'Vpcs[].{VpcId:VpcId,Name:Tags[?Key==`Name`]|[0].Value}' \
  --output json \
  --no-cli-pager)

while IFS=$'\t' read -r VPC_ID VPC_NAME; do
  [[ -z "${VPC_ID}" ]] && continue

  if [[ ! "${VPC_NAME}" =~ ^Aurora\ Cluster\ gh-action-[[:xdigit:]]+-([0-9]+)-[0-9]+$ ]]; then
    echo "::warning::Skipping ${VPC_ID}: unable to extract a GitHub Actions run ID from '${VPC_NAME}'."
    continue
  fi

  RUN_ID=${BASH_REMATCH[1]}
  if ! RUN_STATUS=$(gh api "repos/${KEYCLOAK_REPOSITORY}/actions/runs/${RUN_ID}" --jq .status 2>/dev/null); then
    LOOKUP_ERROR=$(gh api "repos/${KEYCLOAK_REPOSITORY}/actions/runs/${RUN_ID}" --include 2>&1 || true)
    if grep -qE '^HTTP/[^ ]+ 404 ' <<< "${LOOKUP_ERROR}"; then
      RUN_STATUS=not_found
    else
      echo "::warning::Skipping ${VPC_ID}: unable to look up ${KEYCLOAK_REPOSITORY} Actions run ${RUN_ID}."
      continue
    fi
  fi

  case "${RUN_STATUS}" in
    queued|in_progress|pending|requested|waiting)
      echo "Keeping ${VPC_ID} (${VPC_NAME}): Actions run ${RUN_ID} is ${RUN_STATUS}."
      ;;
    completed|not_found)
      AURORA_CLUSTER=${VPC_NAME#Aurora Cluster }
      if [[ "${DRY_RUN}" == "true" ]]; then
        echo "Dry run: would delete Aurora cluster ${AURORA_CLUSTER} and ${VPC_ID} (${VPC_NAME}); Actions run ${RUN_ID} is ${RUN_STATUS}."
        continue
      fi

      echo "Deleting Aurora cluster ${AURORA_CLUSTER} and ${VPC_ID} (${VPC_NAME}): Actions run ${RUN_ID} is ${RUN_STATUS}."
      if ! DELETE_ERROR=$(AURORA_CLUSTER="${AURORA_CLUSTER}" AURORA_REGION="${AWS_REGION}" "${SCRIPT_DIR}/rds/aurora_delete.sh" 2>&1); then
        DELETE_ERROR=${DELETE_ERROR//$'\n'/' '}
        echo "::warning::Unable to delete Aurora cluster ${AURORA_CLUSTER} and ${VPC_ID}: ${DELETE_ERROR}"
      fi
      ;;
    *)
      echo "::warning::Skipping ${VPC_ID}: Actions run ${RUN_ID} has unexpected status '${RUN_STATUS}'."
      ;;
  esac
done < <(jq -r '.[] | [.VpcId, .Name] | @tsv' <<< "${VPCS_JSON}")
