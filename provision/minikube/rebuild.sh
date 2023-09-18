#!/usr/bin/env bash
if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi
set -e

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
  minikube config set memory 8192
  minikube config set cpus 4
  DRIVER=kvm2
  if [ "$(uname)" == "Darwin" ]; then
    DRIVER=hyperkit
  elif [ "$(expr substr "$(uname -s)" 1 10)" == "MINGW64_NT" ]; then
    DRIVER=hyperv
  fi
  minikube config set driver ${DRIVER}
  minikube config set container-runtime docker
  # the version of Kubernetes needs to be in-sync with `provision-minikube.yml`
  minikube start --disk-size=64GB --container-runtime=docker --driver=${DRIVER} --docker-opt="default-ulimit=nofile=102400:102400" --kubernetes-version=v1.25.3
fi
minikube addons enable ingress
rm -rf .task
echo "Minikube initialized. Now run 'task' to provision it with Keycloak"
