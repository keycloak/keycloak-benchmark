package org.keycloak.benchmark.crossdc.client;

import java.net.http.HttpClient;

import org.jboss.logging.Logger;
import org.keycloak.benchmark.crossdc.AbstractCrossDCTest;
import org.keycloak.benchmark.crossdc.util.PropertyUtils;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.openshift.client.OpenShiftClient;

public class DatacenterInfo implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DatacenterInfo.class);

    private final String namespace;
    private final String keycloakServerURL;
    private final String infinispanServerURL;
    private final String loadbalancerURL;

    private final KeycloakClient keycloak;
    private final ExternalInfinispanClient infinispan;
    private final OpenShiftClient oc;
    private final PrometheusClient prometheus;

    public DatacenterInfo(HttpClient httpClient, int index, boolean activePassive) {
        oc = new KubernetesClientBuilder()
              .withConfig(
                    Config.autoConfigure(PropertyUtils.getRequired(String.format("kubernetes.%d.context", index)))
              )
              .build()
              .adapt(OpenShiftClient.class);

        this.namespace = PropertyUtils.getRequired("deployment.namespace");
        this.infinispanServerURL = getRouteHost("infinispan-service-external");

        if (activePassive) {
            this.keycloakServerURL = "https://" + oc.routes()
                  .inNamespace(namespace)
                  .withName("aws-health-route")
                  .get()
                  .getSpec()
                  .getHost();
        } else {
            this.keycloakServerURL = "https://" + oc.services()
                  .inNamespace(namespace)
                  .withName("accelerator-loadbalancer")
                  .get()
                  .getStatus()
                  .getLoadBalancer()
                  .getIngress()
                  .get(0)
                  .getHostname();
        }

        LOG.infof("Keycloak server URL index %d: %s", index, keycloakServerURL);
        this.loadbalancerURL = getRouteHost("keycloak");

        this.keycloak = new KeycloakClient(httpClient, keycloakServerURL, activePassive);
        this.infinispan = new ExternalInfinispanClient(infinispanServerURL, AbstractCrossDCTest.ISPN_USERNAME, AbstractCrossDCTest.MAIN_PASSWORD);
        this.prometheus = new PrometheusClient(this);
    }

    private String getRouteHost(String app) {
        return "https://" + oc.routes()
              .inNamespace(namespace)
              .withLabel("app", app)
              .list()
              .getItems()
              .stream()
              .findFirst()
              .orElseThrow()
              .getSpec()
              .getHost();
    }

    @Override
    public void close() {
        infinispan.close();
        this.oc.close();
    }

    public String getKeycloakServerURL() {
        return keycloakServerURL;
    }

    public String getInfinispanServerURL() {
        return infinispanServerURL;
    }

    public String getLoadbalancerURL() {
        return loadbalancerURL;
    }

    public KeycloakClient kc() {
        return keycloak;
    }

    public ExternalInfinispanClient ispn() {
        return infinispan;
    }

    public OpenShiftClient oc() {
        return oc;
    }

    public String namespace() {
        return namespace;
    }

    public PrometheusClient prometheus() {
        return prometheus;
    }
}
