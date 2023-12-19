package org.keycloak.benchmark.lb;

import org.jboss.logging.Logger;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.health.LoadBalancerCheckProvider;
import org.keycloak.models.KeycloakSession;

public class TestMultiSiteLoadBalancerCheckProvider implements LoadBalancerCheckProvider {

    protected static final Logger logger = Logger.getLogger(TestMultiSiteLoadBalancerCheckProvider.class);

    private final KeycloakSession session;

    public TestMultiSiteLoadBalancerCheckProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public boolean isDown() {
        String siteName = session.getProvider(InfinispanConnectionProvider.class).getTopologyInfo().getMySiteName();
        boolean isDown = session.realms().getRealmByName("master").getAttribute("is-site-" + siteName + "-down", false);

        logger.debugf("Site %s is down %s", siteName, isDown);
        return isDown;
    }

    @Override
    public void close() {

    }
}
