#!/usr/bin/env bash
set -euxo pipefail

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts

kubectl create namespace monitoring || true
helm upgrade --install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml
helm upgrade --install monitoring --set hostname=$(minikube ip).nip.io monitoring
helm upgrade --install jaeger jaegertracing/jaeger -n monitoring -f jaeger.yaml
helm upgrade --install tempo grafana/tempo -n monitoring -f tempo.yaml
helm upgrade --install loki grafana/loki -n monitoring -f loki.yaml
helm upgrade --install promtail grafana/promtail -n monitoring -f promtail.yaml

kubectl create namespace keycloak || true
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/keycloaks.k8s.keycloak.org-v1.yml
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/keycloakrealmimports.k8s.keycloak.org-v1.yml
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/kubernetes.yml
helm upgrade --install keycloak --set hostname=$(minikube ip).nip.io keycloak
