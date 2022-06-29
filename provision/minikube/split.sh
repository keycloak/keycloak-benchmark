#!/usr/bin/env bash
set -euo pipefail
yq --help > /dev/null
mkdir -p .task
rm -f .task/subtask-*.yaml
for i in $(yq -M e '.tasks | keys' Taskfile.yaml); do
   yq -M e ".tasks.${i}" Taskfile.yaml > .task/subtask-${i}.yaml
done
