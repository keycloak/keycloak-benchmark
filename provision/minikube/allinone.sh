#!/usr/bin/env bash
set -x
minikube delete
minikube config set memory 8192
minikube config set cpus 4
minikube start --driver=kvm2 --docker-opt="default-ulimit=nofile=102400:102400"
minikube addons enable ingress
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
kubectl create namespace monitoring
helm install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml
helm install monitoring --set hostname=$(minikube ip).nip.io monitoring
helm install tempo grafana/tempo -n monitoring -f tempo.yaml
helm install keycloak --set hostname=$(minikube ip).nip.io keycloak