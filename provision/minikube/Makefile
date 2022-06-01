MINIKUBE_CPUS ?= "4"
MINIKUBE_MEM ?= "8192"
MINIKUBE_DRIVER ?= $(shell [ $$(uname) == "Darwin" ] && echo "hyperkit" || echo "kvm2" )

.PHONY: all
all: | print-config destroy-minikube spawn-minikube install-monitoring install-keycloak

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
	helm repo add grafana https://grafana.github.io/helm-charts | true
	helm repo add prometheus-community https://prometheus-community.github.io/helm-charts | true
	helm repo update
	kubectl create namespace monitoring | true
	helm install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml | true
	helm install monitoring --set hostname=$(shell minikube ip).nip.io monitoring | true
	helm install tempo grafana/tempo -n monitoring -f tempo.yaml | true

.PHONY: all
uninstall-monitoring:
	helm uninstall prometheus prometheus-community/kube-prometheus-stack | true
	helm uninstall monitoring monitoring | true
	helm uninstall tempo grafana/tempo -n monitoring | true
	kubectl delete namespace monitoring | true

.PHONY: all
install-keycloak:
	helm install keycloak --set hostname=$(shell minikube ip).nip.io keycloak | true

.PHONY: all
uninstall-keycloak:
	helm uninstall keycloak keycloak | true
