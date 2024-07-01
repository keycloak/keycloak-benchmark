package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.keycloak.benchmark.crossdc.util.HttpClientUtils.MOCK_COOKIE_MANAGER;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLIENT_SESSION_CACHE_NAME;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLUSTERED_CACHE_NAMES;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.USER_SESSION_CACHE_NAME;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.WORK_CACHE_NAME;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
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
import org.keycloak.benchmark.crossdc.client.InfinispanClient;
import org.keycloak.benchmark.crossdc.client.KeycloakClient;
import org.keycloak.benchmark.crossdc.junit.tags.ActivePassive;
import org.keycloak.benchmark.crossdc.util.HttpClientUtils;
import org.keycloak.benchmark.crossdc.util.PropertyUtils;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import jakarta.ws.rs.NotFoundException;

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
    public static final boolean SKIP_EMBEDDED_CACHES = Boolean.getBoolean("skipEmbeddedCaches");

    public AbstractCrossDCTest() {
        var httpClient = HttpClientUtils.newHttpClient();
        this.activePassive = System.getProperty("deployment.type", ActivePassive.TAG).equals(ActivePassive.TAG);
        this.DC_1 = new DatacenterInfo(httpClient, 1, activePassive);
        this.DC_2 = new DatacenterInfo(httpClient, 2, activePassive);
        this.LOAD_BALANCER_KEYCLOAK = new KeycloakClient(httpClient, DC_1.getLoadbalancerURL(), activePassive);
    }

    @BeforeEach
    public void setUpTestEnvironment() throws URISyntaxException, IOException, InterruptedException {
        failbackLoadBalancers();
        assertTrue(DC_1.kc().isActive(LOAD_BALANCER_KEYCLOAK));

        Keycloak adminClient = DC_1.kc().adminClient();
        LOG.info("Setting up test environment");
        LOG.info("-------------------------------------------");
        LOG.info("Status of caches before test:");
        Arrays.stream(CLUSTERED_CACHE_NAMES)
                .filter(cache -> !cache.equals(WORK_CACHE_NAME))
                .forEach(cache -> {
                    var entriesDc1 = DC_1.ispn().cache(cache).keys();
                    var entriesDc2 = DC_2.ispn().cache(cache).keys();
                    LOG.infof("External cache %s in DC1: (%d) -> %s", cache, entriesDc1.size(), entriesDc1);
                    LOG.infof("External cache %s in DC2: (%d) -> %s", cache, entriesDc2.size(), entriesDc2);
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

        clearCache(DC_1, USER_SESSION_CACHE_NAME);
        clearCache(DC_1, CLIENT_SESSION_CACHE_NAME);
        clearCache(DC_2, USER_SESSION_CACHE_NAME);
        clearCache(DC_2, CLIENT_SESSION_CACHE_NAME);
        assertCacheSize(USER_SESSION_CACHE_NAME, 0);
        assertCacheSize(CLIENT_SESSION_CACHE_NAME, 0);
    }

    private void clearCache(DatacenterInfo dc, String cache) {
        dc.ispn().cache(cache).clear();
        if (cache.equals(USER_SESSION_CACHE_NAME) || cache.equals(CLIENT_SESSION_CACHE_NAME)) {
            // those sessions will have been invalidated
            KeycloakClient.cleanAdminClients();
        }
        if (SKIP_EMBEDDED_CACHES) {
            return;
        }
        dc.kc().embeddedIspn().cache(cache).clear();
    }

    @AfterEach
    public void tearDownTestEnvironment() throws URISyntaxException, IOException, InterruptedException {
        Keycloak adminClient = DC_1.kc().adminClient();

        if (adminClient.realms().realm(REALM_NAME).toRepresentation() != null) {
            adminClient.realms().realm(REALM_NAME).remove();
        }

        // Logout all users in master realm
        adminClient.realm("master").logoutAll();

        // Clear admin clients
        KeycloakClient.cleanAdminClients();

        Arrays.stream(CLUSTERED_CACHE_NAMES)
                .filter(cache -> !cache.equals(WORK_CACHE_NAME))
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
        assertEmbeddedCacheSizeInAllDCs(cache, size);

        // External caches
        assertExternalCacheSizeInAllDCs(cache, size);
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
                if (condition.get()) return;
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

    private static void assertCacheSize(InfinispanClient.Cache cache, long expectedSize) {
        assertEquals(expectedSize, cache.size(), String.format("Embedded cache %s has an incorrect size", cache.name()));
    }

    protected static void assertEmbeddedCacheSize(DatacenterInfo datacenterInfo, String cacheName, long expectedSize) {
        if (SKIP_EMBEDDED_CACHES) {
            return;
        }
        assertCacheSize(datacenterInfo.kc().embeddedIspn().cache(cacheName), expectedSize);
    }

    protected void assertEmbeddedCacheSizeInAllDCs(String cacheName, long expectedSize) {
        assertEmbeddedCacheSize(DC_1, cacheName, expectedSize);
        assertEmbeddedCacheSize(DC_2, cacheName, expectedSize);
    }

    protected static void assertExternalCacheSize(DatacenterInfo datacenterInfo, String cacheName, long expectedSize) {
        assertCacheSize(datacenterInfo.ispn().cache(cacheName), expectedSize);
    }

    protected void assertExternalCacheSizeInAllDCs(String cacheName, long expectedSize) {
        assertExternalCacheSize(DC_1, cacheName, expectedSize);
        assertExternalCacheSize(DC_2, cacheName, expectedSize);
    }

    private static void assertContains(InfinispanClient.Cache cache, String expectedKey, boolean expectedResult) throws URISyntaxException, IOException, InterruptedException {
        assertEquals(expectedResult, cache.contains(expectedKey), String.format("Expects key '%s' in cache '%s'", expectedKey, cache.name()));
    }

    protected static void assertEmbeddedCacheContains(DatacenterInfo datacenterInfo, String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        if (SKIP_EMBEDDED_CACHES) {
            return;
        }
        assertContains(datacenterInfo.kc().embeddedIspn().cache(cacheName), expectedKey, true);
    }

    protected static void assertEmbeddedCacheNotContains(DatacenterInfo datacenterInfo, String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        if (SKIP_EMBEDDED_CACHES) {
            return;
        }
        assertContains(datacenterInfo.kc().embeddedIspn().cache(cacheName), expectedKey, false);
    }

    protected static void assertExternalCacheContains(DatacenterInfo datacenterInfo, String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        assertContains(datacenterInfo.ispn().cache(cacheName), expectedKey, true);
    }

    protected static void assertExternalCacheNotContains(DatacenterInfo datacenterInfo, String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        assertContains(datacenterInfo.ispn().cache(cacheName), expectedKey, false);
    }

    protected void assertEmbeddedCacheContainsAllDCs(String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        assertEmbeddedCacheContains(DC_1, cacheName, expectedKey);
        assertEmbeddedCacheContains(DC_2, cacheName, expectedKey);
    }

    protected void assertExternalCacheContainsAllDCs(String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        assertExternalCacheContains(DC_1, cacheName, expectedKey);
        assertExternalCacheContains(DC_2, cacheName, expectedKey);
    }

    protected void assertEmbeddedCacheNotContainsAllDCs(String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        assertEmbeddedCacheNotContains(DC_1, cacheName, expectedKey);
        assertEmbeddedCacheNotContains(DC_2, cacheName, expectedKey);
    }

    protected void assertExternalCacheNotContainsAllDCs(String cacheName, String expectedKey) throws URISyntaxException, IOException, InterruptedException {
        assertExternalCacheNotContains(DC_1, cacheName, expectedKey);
        assertExternalCacheNotContains(DC_2, cacheName, expectedKey);
    }
}
