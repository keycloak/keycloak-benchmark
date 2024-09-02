package org.keycloak.benchmark.crossdc;

import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.keycloak.benchmark.crossdc.client.KeycloakClient;
import org.keycloak.benchmark.crossdc.util.HttpClientUtils;
import org.keycloak.benchmark.crossdc.util.KeycloakUtils;
import org.keycloak.common.util.Time;
import org.keycloak.models.sessions.infinispan.entities.RootAuthenticationSessionEntity;
import org.keycloak.representations.idm.ClientRepresentation;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.keycloak.benchmark.crossdc.util.HttpClientUtils.MOCK_COOKIE_MANAGER;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.AUTHENTICATION_SESSIONS_CACHE_NAME;

public class ConcurrentModificationTest extends AbstractCrossDCTest {

    private static final Logger LOG = Logger.getLogger(ConcurrentModificationTest.class);

    @Test
    public void testConcurrentClientSessionAddition() throws IOException, URISyntaxException, InterruptedException {
        assumeTrue(SKIP_EMBEDDED_CACHES && SKIP_REMOTE_CACHES, "Test is applicable only for Persistent sessions at the moment");

        /* This should not run for embedded caches with A/P setup because client sessions are asynchronously
           propagated to the remote Infinispan's embedded caches. Due to that, some client sessions are lost.
           Due to that, the code-to-token exchange then fails. */

        final var ITERATIONS = 20;

        Map<String, String> clientIdToId = new HashMap<>();
        // Create clients that will later log in concurrently
        for (int i = 0; i < ITERATIONS * 3; i++) {
            // Create client
            ClientRepresentation client = new ClientRepresentation();
            client.setEnabled(Boolean.TRUE);
            client.setClientId("client-" + i);
            client.setSecret(CLIENT_SECRET);
            client.setRedirectUris(List.of("*"));
            client.setDirectAccessGrantsEnabled(true);
            client.setProtocol("openid-connect");
            clientIdToId.put(client.getClientId(), KeycloakUtils.getCreatedId(DC_1.kc().adminClient().realm(REALM_NAME).clients().create(client)));
        }

        // Create user session with the main client
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin(REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        String userSessionId = code.split("[.]")[1];

        // completing the login flow
        LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        // Copy cookies also to the other DCs URLs, so we can log in to the same user session from DC_1 and DC_2 URLs
        CookieManager mockCookieManager = MOCK_COOKIE_MANAGER;
        List<HttpCookie> copy = List.copyOf(mockCookieManager.getCookieStore().getCookies());
        copy.forEach(cookie -> {
            if (cookie.getDomain().startsWith(LOAD_BALANCER_KEYCLOAK.getKeycloakServerUrl().substring("https://".length()))) {
                mockCookieManager.getCookieStore().add(null, createCookie(cookie, DC_1.kc().getKeycloakServerUrl().substring("https://".length())));
                mockCookieManager.getCookieStore().add(null, createCookie(cookie, DC_2.kc().getKeycloakServerUrl().substring("https://".length())));
            }
        });

        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger failureCounter = new AtomicInteger(0);
        RuntimeException parentException = new RuntimeException("Test failed, see suppressed exceptions for more details.");

        Random rand = new Random();

        for (int i = 0; i < ITERATIONS; i++) {
            LOG.infof("Starting with the concurrent requests iteration %d", i);

            final var iteration = i;
            // Create new client session with each client in DC1 or DC2 3 times concurrently by opening the login screen.
            // We are already logged in due to the cookies set above.
            IntStream.range(0, 3).parallel().forEach(j -> {
                HttpResponse<String> stringHttpResponse = null;
                KeycloakClient keycloakClient = rand.nextBoolean() ? DC_1.kc() : DC_2.kc();
                String clientId = "client-" + counter.getAndIncrement();
                LOG.infof("Iteration [%d, %d] - ClientID: %s, id: %s, DC: %s ", iteration, j, clientId, clientIdToId.get(clientId), keycloakClient == DC_1.kc() ? "DC1" : "DC2");
                long start = Time.currentTimeMillis();
                try {
                    // The following line creates the new client session as the user is already logged in with a cookie.
                    // If this line fails it might indicate a deadlock on the Keycloak server side.
                    stringHttpResponse = keycloakClient.openLoginForm(REALM_NAME, clientId);

                    String code2 = KeycloakUtils.extractCodeFromResponse(stringHttpResponse);
                    String userSessionId2 = code2.split("[.]")[1];
                    assertEquals(userSessionId, userSessionId2, "Expecting the same user session as on the initial login");

                    // Completing the login flow.
                    // This will only work if the client session was written successfully to the store.
                    keycloakClient.exchangeCode(REALM_NAME, clientId, CLIENT_SECRET, 200, code2);
                } catch (Throwable e) {
                    // Failure occurred, increment the counter and add the exception with details to the parent exception
                    failureCounter.incrementAndGet();
                    var ex = new RuntimeException("Interation [" + iteration + ", " + j + "] - Failed" + (stringHttpResponse != null ? " with error response: " + stringHttpResponse.body() : ""));
                    ex.addSuppressed(e);
                    parentException.addSuppressed(ex);
                } finally {
                    LOG.infof("Iteration [%d, %d] - Time: %d", iteration, j, Time.currentTimeMillis() - start);
                }
            });

            LOG.infof("Finished with the concurrent requests iteration %d", i);
        }

        if (failureCounter.get() > 0) {
            throw parentException;
        }
    }

    @Test
    void testConcurrentAuthenticationSessionsAddition() throws IOException, URISyntaxException, InterruptedException {
        RemoteCache<String, RootAuthenticationSessionEntity> authenticationSessions = (RemoteCache<String, RootAuthenticationSessionEntity>) DC_1.ispn().cache(AUTHENTICATION_SESSIONS_CACHE_NAME).getRemoteCache();
        authenticationSessions.clear();

        HttpResponse<String> stringHttpResponse = LOAD_BALANCER_KEYCLOAK.openLoginForm(REALM_NAME, CLIENTID);
        assertEquals(200, stringHttpResponse.statusCode());

        int ITERATIONS = 20;
        IntStream.range(0, ITERATIONS).parallel().forEach(j -> {
            try {
                HttpResponse<String> response = LOAD_BALANCER_KEYCLOAK.openLoginForm(REALM_NAME, CLIENTID);
                assertEquals(200, response.statusCode());
            } catch (IOException | InterruptedException | URISyntaxException e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(1, authenticationSessions.size());
        try (var iter = authenticationSessions.values().iterator()) {
            assertEquals(ITERATIONS + 1, iter.next().getAuthenticationSessions().size());
        }

    }

    private HttpCookie createCookie(HttpCookie oldCookie, String domain) {
        HttpCookie cookie = new HttpCookie(oldCookie.getName(), oldCookie.getValue());
        cookie.setDomain(domain);
        cookie.setPath(oldCookie.getPath());
        cookie.setVersion(oldCookie.getVersion());
        cookie.setSecure(oldCookie.getSecure());
        cookie.setHttpOnly(oldCookie.isHttpOnly());
        return cookie;
    }
}
