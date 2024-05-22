package org.keycloak.benchmark.crossdc.client;

import org.keycloak.benchmark.crossdc.AbstractCrossDCTest;

import java.net.http.HttpClient;

public class DatacenterInfo {

    private final String keycloakServerURL;
    private final String infinispanServerURL;

    private final KeycloakClient keycloak;
    private final ExternalInfinispanClient infinispan;

    public DatacenterInfo(HttpClient httpClient, String keycloakServerURL, String infinispanServerURL) {
        this.keycloak = new KeycloakClient(httpClient, keycloakServerURL);
        this.infinispan = new ExternalInfinispanClient(httpClient, infinispanServerURL, AbstractCrossDCTest.ISPN_USERNAME, AbstractCrossDCTest.MAIN_PASSWORD, keycloakServerURL);

        this.keycloakServerURL = keycloakServerURL;
        this.infinispanServerURL = infinispanServerURL;
    }
    public String getKeycloakServerURL() {
        return keycloakServerURL;
    }

    public String getInfinispanServerURL() {
        return infinispanServerURL;
    }

    public KeycloakClient kc() {
        return keycloak;
    }

    public ExternalInfinispanClient ispn() {
        return infinispan;
    }
}
