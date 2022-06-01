helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts

helm install prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml \
|| helm upgrade prometheus prometheus-community/kube-prometheus-stack -f monitoring.yaml

helm install monitoring --set hostname=$(minikube ip).nip.io monitoring \
|| helm upgrade monitoring --set hostname=$(minikube ip).nip.io monitoring

helm install jaeger jaegertracing/jaeger -n monitoring -f jaeger.yaml \
|| helm upgrade jaeger jaegertracing/jaeger -n monitoring -f jaeger.yaml

helm install tempo grafana/tempo -n monitoring -f tempo.yaml \
|| helm upgrade tempo grafana/tempo -n monitoring -f tempo.yaml

helm install loki grafana/loki -n monitoring -f loki.yaml \
|| helm upgrade loki grafana/loki -n monitoring -f loki.yaml

helm install promtail grafana/promtail -n monitoring -f promtail.yaml \
|| helm upgrade promtail grafana/promtail -n monitoring -f promtail.yaml

helm install keycloak --set hostname=$(minikube ip).nip.io keycloak \
|| helm upgrade keycloak --set hostname=$(minikube ip).nip.io keycloak
