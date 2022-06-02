#!/usr/bin/env bash
set -x
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
  minikube start --driver=${DRIVER} --docker-opt="default-ulimit=nofile=102400:102400"
fi
minikube addons enable ingress
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
kubectl create namespace monitoring
helm upgrade --install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml
helm upgrade --install monitoring --set hostname=$(minikube ip).nip.io monitoring
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
helm upgrade --install jaeger jaegertracing/jaeger -n monitoring -f jaeger.yaml
helm repo add grafana https://grafana.github.io/helm-charts
helm upgrade --install --debug tempo grafana/tempo -n monitoring -f tempo.yaml
helm upgrade --install --debug loki grafana/loki -n monitoring -f loki.yaml
helm upgrade --install --debug promtail grafana/promtail -n monitoring -f promtail.yaml
kubectl create namespace keycloak
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/keycloaks.k8s.keycloak.org-v1.yml
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/keycloakrealmimports.k8s.keycloak.org-v1.yml
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/kubernetes.yml
helm upgrade --install --debug keycloak --set hostname=$(minikube ip).nip.io keycloak
echo -e "use\n\n  kubectl get pods -A -w\n\nto get information about starting pods\n\n"
echo -e "use\n\n  ./isup.sh\n\nto wait for all services to become available\n\n"
