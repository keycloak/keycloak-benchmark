package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.keycloak.benchmark.crossdc.util.HttpClientUtils.MOCK_COOKIE_MANAGER;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.CLIENT_SESSIONS;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.DISTRIBUTED_CACHES;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.SESSIONS;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.benchmark.crossdc.client.AWSClient;
import org.keycloak.benchmark.crossdc.client.DatacenterInfo;
import org.keycloak.benchmark.crossdc.client.KeycloakClient;
import org.keycloak.benchmark.crossdc.junit.tags.ActivePassive;
import org.keycloak.benchmark.crossdc.util.HttpClientUtils;
import org.keycloak.benchmark.crossdc.util.InfinispanUtils;
import org.keycloak.benchmark.crossdc.util.K8sUtils;
import org.keycloak.benchmark.crossdc.util.PropertyUtils;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.ws.rs.NotFoundException;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractCrossDCTest {
    private static final Logger LOG = Logger.getLogger(AbstractCrossDCTest.class);
    protected final DatacenterInfo DC_1, DC_2;
    protected final KeycloakClient LOAD_BALANCER_KEYCLOAK;

    protected final boolean activePassive;
    public static String ISPN_USERNAME = System.getProperty("infinispan.username", "developer");
    public static final String REALM_NAME = "cross-dc-test-realm";
    public static final String CLIENTID = "cross-dc-test-client";
    public static final String CLIENT_SECRET = "cross-dc-test-client-secret";
    public static final String USERNAME = "cross-dc-test-user";
    public static final String MAIN_PASSWORD = PropertyUtils.getRequired("main.password");

    public AbstractCrossDCTest() {
        var httpClient = HttpClientUtils.newHttpClient();
        this.activePassive = System.getProperty("deployment.type", ActivePassive.TAG).equals(ActivePassive.TAG);
        this.DC_1 = new DatacenterInfo(httpClient, 1, activePassive);
        this.DC_2 = new DatacenterInfo(httpClient, 2, activePassive);
        this.LOAD_BALANCER_KEYCLOAK = new KeycloakClient(httpClient, DC_1.getLoadbalancerURL(), activePassive);
    }

    @BeforeEach
    public void setUpTestEnvironment() throws URISyntaxException, IOException, InterruptedException, UnknownHostException {
        failbackLoadBalancers();
        assertTrue(DC_1.kc().isActive(LOAD_BALANCER_KEYCLOAK));

        Keycloak adminClient = DC_1.kc().adminClient();
        LOG.info("Setting up test environment");
        LOG.info("-------------------------------------------");
        LOG.info("Status of caches before test:");
        DISTRIBUTED_CACHES
                .stream()
                .filter(cache -> !cache.equals(InfinispanUtils.WORK))
                .forEach(cache -> {
            LOG.infof("External cache %s " + cache + " in DC1: %d - entries [%s]", cache, DC_1.ispn().cache(cache).size(), DC_1.ispn().cache(cache).keys());
            LOG.infof("External cache %s " + cache + " in DC2: %d - entries [%s]", cache, DC_2.ispn().cache(cache).size(), DC_2.ispn().cache(cache).keys());
        });
        LOG.info("-------------------------------------------");

        try {
            if (adminClient.realms().realm(REALM_NAME).toRepresentation() != null) {
                adminClient.realms().realm(REALM_NAME).remove();
            }
        } catch (NotFoundException e) {
            // Ignore
        }

        // Create realm
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(REALM_NAME);
        realm.setEnabled(Boolean.TRUE);
        adminClient.realms().create(realm);

        RealmResource realmResource = adminClient.realm(REALM_NAME);

        // Create client
        ClientRepresentation client = new ClientRepresentation();
        client.setEnabled(Boolean.TRUE);
        client.setClientId(CLIENTID);
        client.setSecret(CLIENT_SECRET);
        client.setRedirectUris(List.of("*"));
        client.setDirectAccessGrantsEnabled(true);
        client.setProtocol("openid-connect");
        realmResource.clients().create(client).close();

        // Create user
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(Boolean.TRUE);
        user.setUsername(USERNAME);
        user.setFirstName(user.getUsername());
        user.setLastName(user.getUsername());
        user.setEmail(user.getUsername() + "@email.email");

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(MAIN_PASSWORD);
        user.setCredentials(Collections.singletonList(credential));

        realmResource.users().create(user).close();

        clearCache(DC_1, SESSIONS);
        clearCache(DC_1, CLIENT_SESSIONS);
        clearCache(DC_2, SESSIONS);
        clearCache(DC_2, CLIENT_SESSIONS);
        assertCacheSize(SESSIONS, 0);
        assertCacheSize(CLIENT_SESSIONS, 0);
    }

    private void clearCache(DatacenterInfo dc, String cache) {
        dc.kc().embeddedIspn().cache(cache).clear();
        dc.ispn().cache(cache).clear();
        if (cache.equals(SESSIONS) || cache.equals(CLIENT_SESSIONS)) {
            // those sessions will have been invalidated
            KeycloakClient.cleanAdminClients();
        }
    }

    @AfterEach
    public void tearDownTestEnvironment() throws URISyntaxException, IOException, InterruptedException {
        Keycloak adminClient = DC_1.kc().adminClient();

        if (adminClient.realms().realm(REALM_NAME).toRepresentation() != null) {
            adminClient.realms().realm(REALM_NAME).remove();
        }

        // Logout all users in master realm
        DC_1.kc().adminClient().realm("master").logoutAll();

        // Clear admin clients
        KeycloakClient.cleanAdminClients();

        DISTRIBUTED_CACHES.stream()
              .filter(cache -> !cache.equals(InfinispanUtils.WORK))
              .forEach(cache -> {
                  DC_1.ispn().cache(cache).clear();
                  DC_2.ispn().cache(cache).clear();
              });

        MOCK_COOKIE_MANAGER.getCookieStore().removeAll();
        failbackLoadBalancers();
    }

    @AfterAll
    public void tearDown() throws URISyntaxException, IOException, InterruptedException {
        failbackLoadBalancers();
        DC_1.close();
        DC_2.close();
    }

    protected void failbackLoadBalancers() throws URISyntaxException, IOException, InterruptedException {
        DC_1.kc().markLBCheckUp();
        DC_2.kc().markLBCheckUp();
    }

    protected void assertCacheSize(String cache, int size) {
        // Embedded caches
        assertEquals(size, DC_1.kc().embeddedIspn().cache(cache).size(), () -> "Embedded cache " + cache + " in DC1 has " + DC_1.ispn().cache(cache).size() + " entries");
        assertEquals(size, DC_2.kc().embeddedIspn().cache(cache).size(), () -> "Embedded cache " + cache + " in DC2 has " + DC_2.ispn().cache(cache).size() + " entries");

        // External caches
        assertEquals(size, DC_1.ispn().cache(cache).size(), () -> "External cache " + cache + " in DC1 has " + DC_1.ispn().cache(cache).size() + " entries");
        assertEquals(size, DC_2.ispn().cache(cache).size(), () -> "External cache " + cache + " in DC2 has " + DC_2.ispn().cache(cache).size() + " entries");
    }

    protected void waitForAcceleratorEndpointCount(int count) {
        eventually(
              () -> String.format("Expected the Accelerator EndpointGroup size to be %d", count),
              () -> AWSClient.getAcceleratorEndpoints(DC_1.getLoadbalancerURL()).size() == count,
              2, TimeUnit.MINUTES
        );
    }

    protected void eventually(Supplier<String> messageSupplier, Supplier<Boolean> condition) {
        eventually(messageSupplier, condition, 30, TimeUnit.SECONDS);
    }

    protected void eventually(Supplier<String> messageSupplier, Supplier<Boolean> condition, long timeout, TimeUnit timeUnit) {
        try {
            long timeoutNanos = timeUnit.toNanos(timeout);
            // We want the sleep time to increase in arithmetic progression
            // 30 loops with the default timeout of 30 seconds means the initial wait is ~ 65 millis
            int loops = 30;
            int progressionSum = loops * (loops + 1) / 2;
            long initialSleepNanos = timeoutNanos / progressionSum;
            long sleepNanos = initialSleepNanos;
            long expectedEndTime = System.nanoTime() + timeoutNanos;
            while (expectedEndTime - System.nanoTime() > 0) {
                if (condition.get())
                    return;
                LockSupport.parkNanos(sleepNanos);
                sleepNanos += initialSleepNanos;
            }
            if (!condition.get()) {
                fail(messageSupplier.get());
            }
        } catch (Exception e) {
            throw new RuntimeException("Unexpected Exception during eventually!", e);
        }
    }
}
