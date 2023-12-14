package org.keycloak.benchmark.lb;

import org.keycloak.Config;
import org.keycloak.health.LoadBalancerCheckProvider;
import org.keycloak.health.LoadBalancerCheckProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class TestMultiSiteLoadBalancerCheckProviderFactory implements LoadBalancerCheckProviderFactory {

    @Override
    public LoadBalancerCheckProvider create(KeycloakSession keycloakSession) {
        return new TestMultiSiteLoadBalancerCheckProvider(keycloakSession);
    }

    @Override
    public void init(Config.Scope scope) {

    }

    @Override
    public void postInit(KeycloakSessionFactory keycloakSessionFactory) {

    }

    @Override
    public void close() {

    }

    @Override
    public String getId() {
        return "test-multisite";
    }
}
