#!/usr/bin/env bash
set -x
DRIVER=kvm2
if [ "$(uname)" == "Darwin" ]; then
  DRIVER=hyperkit
elif [ "$(expr substr $(uname -s) 1 10)" == "MINGW64_NT" ]; then
  DRIVER=hyperv
fi
minikube start --driver=${DRIVER} --docker-opt="default-ulimit=nofile=102400:102400"
sleep 10
helm upgrade monitoring --set hostname=$(minikube ip).nip.io monitoring
helm upgrade keycloak --set hostname=$(minikube ip).nip.io keycloak
./isup.sh
