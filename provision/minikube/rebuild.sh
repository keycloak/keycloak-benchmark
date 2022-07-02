#!/usr/bin/env bash
# set -x
set -e
if [ "$GITHUB_ACTIONS" == "" ]; then
  minikube delete
  minikube config set memory 8192
  minikube config set cpus 4
  DRIVER=kvm2
  if [ "$(uname)" == "Darwin" ]; then
    DRIVER=hyperkit
  elif [ "$(expr substr $(uname -s) 1 10)" == "MINGW64_NT" ]; then
    DRIVER=hyperv
  fi
  minikube config set driver ${DRIVER}
  minikube config set container-runtime cri-o
  minikube start --container-runtime=cri-o --driver=${DRIVER} --docker-opt="default-ulimit=nofile=102400:102400"
fi
minikube addons enable ingress
echo -e "use\n\n  kubectl get pods -A -w\n\nto get information about starting pods\n\n"
task -f
