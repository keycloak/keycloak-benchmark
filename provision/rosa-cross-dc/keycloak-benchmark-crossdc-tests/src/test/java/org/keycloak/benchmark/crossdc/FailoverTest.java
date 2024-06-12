package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.SESSIONS;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.keycloak.benchmark.crossdc.client.AWSClient;
import org.keycloak.benchmark.crossdc.client.DatacenterInfo;
import org.keycloak.benchmark.crossdc.junit.tags.ActiveActive;
import org.keycloak.benchmark.crossdc.junit.tags.ActivePassive;

import io.fabric8.kubernetes.client.KubernetesClient;

public class FailoverTest extends AbstractCrossDCTest {

    static final String OPERATORS_NS = "openshift-operators";

    @Override
    protected void failbackLoadBalancers() throws URISyntaxException, IOException, InterruptedException {
        super.failbackLoadBalancers();
        if (activePassive) {
            String domain = DC_1.getKeycloakServerURL().substring("https://".length());
            AWSClient.updateRoute53HealthCheckPath(domain, "/lb-check");
            DC_1.kc().waitToBeActive(LOAD_BALANCER_KEYCLOAK);
        } else {
            // Heal split-brain if previously initiated
            scaleUpGossipRouter(DC_1);
            scaleUpGossipRouter(DC_2);
            // Wait for JGroups site view to contain both sites
            AWSClient.acceleratorFallback(LOAD_BALANCER_KEYCLOAK.getKeycloakServerUrl());
            // Assert that both sites are part of the Accelerator EndpointGroup
            waitForAcceleratorEndpointCount(2);
        }
    }

    @Test
    @ActivePassive
    public void logoutUserWithFailoverTest() throws IOException, URISyntaxException, InterruptedException {
        // Login and exchange code in DC1
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin( REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        DC_1.kc().markLBCheckDown();
        DC_2.kc().waitToBeActive(LOAD_BALANCER_KEYCLOAK);

        // Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC2
        Set<String> sessions = DC_2.ispn().cache(SESSIONS).keys();
        assertTrue(sessions.contains(code.split("[.]")[1]));

        tokensMap = LOAD_BALANCER_KEYCLOAK.refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 200);

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        LOAD_BALANCER_KEYCLOAK.refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
    }

    @Test
    @ActiveActive
    public void ensureAcceleratorUpdatedOnSplitBrainTest() throws Exception {
        // Assert that both sites are part of the Accelerator EndpointGroup
        assertEquals(2, AWSClient.getAcceleratorEndpoints(DC_1.getLoadbalancerURL()).size());

        // Trigger a split-brain by scaling down the GossipRouter in both sites
        scaleDownGossipRouter(DC_1);
        scaleDownGossipRouter(DC_2);

        // Wait for both sites to detect split-brain
        waitForSitesViewCount(1);

        // Assert that the AWS Lambda was executed and that only one site LB remains in the Accelerator EndpointGroup
        waitForAcceleratorEndpointCount(1);
    }

    private void waitForAcceleratorEndpointCount(int count) {
        eventually(
              () -> String.format("Expected the Accelerator EndpointGroup size to be %d", count),
              () -> AWSClient.getAcceleratorEndpoints(DC_1.getLoadbalancerURL()).size() == count,
              2, TimeUnit.MINUTES
        );
    }

    private void waitForSitesViewCount(int count) {
        Supplier<String> msg = () -> "Timedout waiting for cross-site view to reform";
        eventually(msg, () -> DC_1.ispn().getSiteView().size() == count);
        eventually(msg, () -> DC_2.ispn().getSiteView().size() == count);
    }

    private void scaleDownGossipRouter(DatacenterInfo datacenter) throws InterruptedException {
        var oc = datacenter.oc();
        scaleDeployment(oc, "infinispan-operator-controller-manager", OPERATORS_NS, 0);
        scaleDeployment(oc, "infinispan-router", datacenter.namespace(), 0);
    }

    private void scaleUpGossipRouter(DatacenterInfo datacenter) throws InterruptedException {
        var oc = datacenter.oc();
        scaleDeployment(oc, "infinispan-operator-controller-manager", OPERATORS_NS, 1);
        scaleDeployment(oc, "infinispan-router", datacenter.namespace(), 1);
    }

    private void scaleDeployment(KubernetesClient k8s, String name, String namespace, int replicas) throws InterruptedException {
        k8s.apps()
              .deployments()
              .inNamespace(namespace)
              .withName(name)
              .scale(replicas);

        k8s.apps()
              .deployments()
              .inNamespace(namespace)
              .withName(name)
              .waitUntilReady(30, TimeUnit.SECONDS);
    }
}
