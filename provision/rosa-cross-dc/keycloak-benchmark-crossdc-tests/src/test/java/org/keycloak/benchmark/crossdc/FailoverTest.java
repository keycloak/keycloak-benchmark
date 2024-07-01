package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.USER_SESSION_CACHE_NAME;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.infinispan.commons.util.ByRef;
import org.junit.jupiter.api.Test;
import org.keycloak.benchmark.crossdc.client.AWSClient;
import org.keycloak.benchmark.crossdc.client.DatacenterInfo;
import org.keycloak.benchmark.crossdc.junit.tags.ActiveActive;
import org.keycloak.benchmark.crossdc.junit.tags.ActivePassive;
import org.keycloak.benchmark.crossdc.util.K8sUtils;

import software.amazon.awssdk.services.cloudwatch.model.StateValue;

public class FailoverTest extends AbstractCrossDCTest {

    static final String SITE_OFFLINE_ALERT = "SiteOffline";

    @Override
    protected void failbackLoadBalancers() throws URISyntaxException, IOException, InterruptedException {
        super.failbackLoadBalancers();
        if (activePassive) {
            String domain = DC_1.getKeycloakServerURL().substring("https://".length());
            String healthCheckId = AWSClient.getHealthCheckId(domain);
            AWSClient.updateRoute53HealthCheckPath(healthCheckId, "/lb-check");
            AWSClient.waitForTheHealthCheckToBeInState(healthCheckId, StateValue.OK);
            DC_1.kc().waitToBeActive(LOAD_BALANCER_KEYCLOAK);

            // Assert that the health check path was updated
            String route53HealthCheckPath = AWSClient.getRoute53HealthCheckPath(healthCheckId);
            assertTrue(route53HealthCheckPath.endsWith("/lb-check"), "Health check path was supposed to end with /lb-check but was " + route53HealthCheckPath);
        } else {
            // Heal split-brain if previously initiated
            scaleGossipRouter(DC_1, 1);
            scaleGossipRouter(DC_2, 1);
            // Wait for JGroups site view to contain both sites
            waitForSitesViewCount(2);
            // Ensure that the SiteOffline alert is no longer firing
            eventually(
                  () -> String.format("Alert '%s' still firing on DC", SITE_OFFLINE_ALERT),
                  () -> !DC_1.prometheus().isAlertFiring(SITE_OFFLINE_ALERT) && !DC_2.prometheus().isAlertFiring(SITE_OFFLINE_ALERT),
                  5, TimeUnit.MINUTES
            );
            // Add both sites to the Accelerator EndpointGroup
            AWSClient.acceleratorFallback(LOAD_BALANCER_KEYCLOAK.getKeycloakServerUrl());
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

        // It seems in some cases the alarm is triggered later than the actual failover happens and the test passes
        //  so quickly that the alarm is still on OK state in the failbackLoadBalancers method which is causing failures
        //  in the following tests, therefore we will wait for the health check to be in ALARM state before proceeding
        String healthCheckId = AWSClient.getHealthCheckId(DC_1.getKeycloakServerURL().substring("https://".length()));
        AWSClient.waitForTheHealthCheckToBeInState(healthCheckId, StateValue.ALARM);
        String route53HealthCheckPath = AWSClient.getRoute53HealthCheckPath(healthCheckId);

        // Check the failover lambda was executed and the health check path was updated to a non-existing url
        assertTrue(route53HealthCheckPath.endsWith("/lb-check-failed-over"), "Health check path was supposed to end with /lb-check-failed-over but was " + route53HealthCheckPath);
        DC_2.kc().waitToBeActive(LOAD_BALANCER_KEYCLOAK);

        // Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC2
        Set<String> sessions = DC_2.ispn().cache(USER_SESSION_CACHE_NAME).keys();
        assertTrue(sessions.contains(code.split("[.]")[1]));

        tokensMap = LOAD_BALANCER_KEYCLOAK.refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 200);

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        LOAD_BALANCER_KEYCLOAK.refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
    }

    @Test
    @ActiveActive
    public void ensureAcceleratorUpdatedOnSplitBrainTest() {
        // Minus one minute to allow for difference in local and AWS clocks
        var startTime = Instant.now().minusSeconds(60);
        var acceleratorMeta = AWSClient.getAcceleratorMeta(DC_1.getLoadbalancerURL());
        var region = acceleratorMeta.endpointGroup().endpointGroupRegion();

        // Ensure that no SiteOffline events are firing on either site
        assertFalse(DC_1.prometheus().isAlertFiring(SITE_OFFLINE_ALERT));
        assertFalse(DC_2.prometheus().isAlertFiring(SITE_OFFLINE_ALERT));

        // Assert that the Lambda has not been executed
        assertEquals(0, AWSClient.getLambdaInvocationCount(acceleratorMeta.name(), region, startTime));

        // Assert that both sites are part of the Accelerator EndpointGroup
        assertEquals(2, AWSClient.getAcceleratorEndpoints(DC_1.getLoadbalancerURL()).size());

        // Trigger a split-brain by scaling down the GossipRouter in both sites
        scaleGossipRouter(DC_1, 0);
        scaleGossipRouter(DC_2, 0);

        // Wait for both sites to detect split-brain
        waitForSitesViewCount(1);

        // Assert that the AWS Lambda was executed and that only one site LB remains in the Accelerator EndpointGroup
        waitForAcceleratorEndpointCount(1);

        // Assert that the Lambda has been triggered by both sites
        ByRef.Long count = new ByRef.Long(0);
        eventually(
              () -> String.format("Expected %d Lambda invocations, got %d", 2, count.get()),
              () -> {
                  count.set(AWSClient.getLambdaInvocationCount(acceleratorMeta.name(), region, startTime));
                  return count.get() == 2;
              },
              10, TimeUnit.MINUTES
        );
    }

    private void waitForSitesViewCount(int count) {
        Supplier<String> msg = () -> "Timedout waiting for cross-site view to reform";
        eventually(msg, () -> DC_1.ispn().getSiteView().size() == count, 5, TimeUnit.MINUTES);
        eventually(msg, () -> DC_2.ispn().getSiteView().size() == count, 5, TimeUnit.MINUTES);
    }

    protected void scaleGossipRouter(DatacenterInfo datacenter, int replicas) {
        var oc = datacenter.oc();
        K8sUtils.scaleDeployment(oc, "infinispan-operator-controller-manager", "openshift-operators", replicas);
        K8sUtils.scaleDeployment(oc, "infinispan-router", datacenter.namespace(), replicas);
    }
}
