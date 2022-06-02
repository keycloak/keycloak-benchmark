MINIKUBE_CPUS ?= "4"
MINIKUBE_MEM ?= "8192"
MINIKUBE_DRIVER ?= $(shell [ $$(uname) == "Darwin" ] && echo "hyperkit" || echo "kvm2" )

.PHONY: all
all: | print-config spawn-minikube install-monitoring install-keycloak

.PHONY: all
print-config:
	@echo "Current configuration:"
	@echo "Minikube driver: \"${MINIKUBE_DRIVER}\""
	@echo "Minikube cpus: \"${MINIKUBE_CPUS}\""
	@echo "Minikube memory: \"${MINIKUBE_MEM}\""

.PHONY: all
destroy-minikube:
	minikube delete

.PHONY: all
spawn-minikube:
	minikube start --driver=${MINIKUBE_DRIVER} --memory ${MINIKUBE_MEM} --cpus ${MINIKUBE_CPUS} --docker-opt="default-ulimit=nofile=102400:102400" --addons=ingress

.PHONY: all
install-monitoring:
	helm repo add grafana https://grafana.github.io/helm-charts || true
	helm repo add prometheus-community https://prometheus-community.github.io/helm-charts || true
	helm repo add jaegertracing https://jaegertracing.github.io/helm-charts || true

	helm repo update
	kubectl create namespace monitoring || true
	helm upgrade --install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml
	helm upgrade --install monitoring --set hostname=$(shell minikube ip).nip.io monitoring
	helm upgrade --install tempo grafana/tempo -n monitoring -f tempo.yaml
	helm upgrade --install jaeger jaegertracing/jaeger -n monitoring -f jaeger.yaml

.PHONY: all
uninstall-monitoring:
	helm uninstall prometheus prometheus-community/kube-prometheus-stack || true
	helm uninstall monitoring monitoring || true
	helm uninstall tempo grafana/tempo -n monitoring || true
	kubectl delete namespace monitoring || true

.PHONY: all
install-keycloak:
	kubectl create namespace keycloak || true
	kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/keycloaks.k8s.keycloak.org-v1.yml
	kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/keycloakrealmimports.k8s.keycloak.org-v1.yml
	kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/nightly/kubernetes/kubernetes.yml
	helm upgrade --install keycloak --set hostname=$(shell minikube ip).nip.io keycloak

.PHONY: all
uninstall-keycloak:
	helm uninstall keycloak keycloak || true
