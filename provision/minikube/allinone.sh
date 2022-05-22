#!/usr/bin/env bash
set -x
minikube delete
minikube config set memory 8192
minikube config set cpus 4
DRIVER=kvm2
if [ "$(uname)" == "Darwin" ]; then
  DRIVER=hyperkit
elif [ "$(expr substr $(uname -s) 1 10)" == "MINGW64_NT" ]; then
  DRIVER=hyperv
fi
minikube start --driver=${DRIVER} --docker-opt="default-ulimit=nofile=102400:102400"
minikube addons enable ingress
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
kubectl create namespace monitoring
helm install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml
helm install monitoring --set hostname=$(minikube ip).nip.io monitoring
helm install tempo grafana/tempo -n monitoring -f tempo.yaml
helm install loki grafana/loki -n monitoring -f loki.yaml
helm install promtail grafana/promtail -n monitoring -f promtail.yaml
helm install keycloak --set hostname=$(minikube ip).nip.io keycloak
echo -e "use\n\n  kubectl get pods -A -w\n\nto get information about starting pods\n\n"
echo -e "use\n\n  ./isup.sh\n\nto wait for all services to become available\n\n"
