#!/usr/bin/env bash
if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi
set -e

if [ -f ./.env ]; then
  source ./.env
fi

MINIKUBE_MEMORY=${MINIKUBE_MEMORY:-8192}
MINIKUBE_CPUS=${MINIKUBE_CPUS:-4}
MINIKUBE_KUBERNETES_VERSION=${MINIKUBE_KUBERNETES_VERSION:-1.30.0}

if [ "$GITHUB_ACTIONS" == "" ]; then
  #prerequisite checks
  #this test is valid only on Linux, and would fail on Windows, MacOS
  if [ "$(uname)" == "Linux" ]; then
   if [ "$(egrep -c 'vmx|svm' /proc/cpuinfo)" -gt 0 ]; then
     echo "PRE-REQ CHECK PASSED: Virtualization is enabled on the host machine, its safe to proceed."
   else
     echo >&2 "PRE-REQ CHECK FAILED: Virtualization is not enabled properly on the host machine. Please check the installation docs."
     exit 1
   fi
  fi

  # this test is valid only on Linux, and would fail on Windows
  if [ "$(uname)" == "Linux" ]; then
    if [ "$(getent group libvirt | grep -c $USER)" -gt 0 ]; then
      echo "PRE-REQ CHECK PASSED: User is found in the libvirt group."
    else
      echo >&2 "PRE-REQ CHECK FAILED: User is not found in the libvirt group. Please check the installation docs."
      exit 1
    fi
  fi

  minikube delete
  minikube config set memory $MINIKUBE_MEMORY
  minikube config set cpus $MINIKUBE_CPUS
  DRIVER=kvm2
  if [ "$(uname)" == "Darwin" ]; then
    DRIVER=hyperkit
  elif [ "$(expr substr "$(uname -s)" 1 10)" == "MINGW64_NT" ]; then
    DRIVER=hyperv
  fi
  minikube config set driver ${DRIVER}
  minikube config set container-runtime docker
  # the version of Kubernetes needs to be in-sync with `provision-minikube.yml`
  minikube start --addons=ingress --disk-size=64GB --container-runtime=docker --driver=${DRIVER} --docker-opt="default-ulimit=nofile=102400:102400" --kubernetes-version=v$MINIKUBE_KUBERNETES_VERSION --cni cilium
fi
rm -rf .task
echo "Minikube initialized. Now run 'task' to provision it with Keycloak"
