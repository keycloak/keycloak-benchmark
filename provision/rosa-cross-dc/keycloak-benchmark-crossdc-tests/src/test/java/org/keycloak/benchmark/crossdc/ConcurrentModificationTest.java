package org.keycloak.benchmark.crossdc;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.keycloak.benchmark.crossdc.client.KeycloakClient;
import org.keycloak.benchmark.crossdc.util.HttpClientUtils;
import org.keycloak.benchmark.crossdc.util.KeycloakUtils;
import org.keycloak.common.util.Time;
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

public class ConcurrentModificationTest extends AbstractCrossDCTest {

    private static final Logger LOG = Logger.getLogger(ConcurrentModificationTest.class);

    @Test
    public void testConcurrentClientSessionAddition() throws IOException, URISyntaxException, InterruptedException {
        final var ITERATIONS = 20;

        Map<String, String> clientIdToId = new HashMap<>();
        // Create clients in DC1
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

        // Create user session with the main client in DC1
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin(REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        String userSessionId = code.split("[.]")[1];

        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        // Create cookies also for the other DCs URLS so we can login to the same user session from DC_1 and DC_2 Urls
        CookieManager mockCookieManager = HttpClientUtils.MOCK_COOKIE_MANAGER;
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
            // Create new client session with each client in DC1 or DC2 3 times concurrently
            IntStream.range(0, 3).parallel().forEach(j -> {
                HttpResponse<String> stringHttpResponse = null;
                KeycloakClient keycloakClient = rand.nextBoolean() ? DC_1.kc() : DC_2.kc();
                String clientId = "client-" + counter.getAndIncrement();
                LOG.infof("Iteration [%d, %d] - ClientID: %s, id: %s, DC: %s ", iteration, j, clientId, clientIdToId.get(clientId), keycloakClient == DC_1.kc() ? "DC1" : "DC2");
                long start = Time.currentTimeMillis();
                try {
                    stringHttpResponse = keycloakClient.openLoginForm(REALM_NAME, clientId);
                    String code2 = KeycloakUtils.extractCodeFromResponse(stringHttpResponse);
                    String userSessionId2 = code2.split("[.]")[1];
                    assertEquals(userSessionId, userSessionId2);
                    Map<String, Object> stringObjectMap = keycloakClient.exchangeCode(REALM_NAME, clientId, CLIENT_SECRET, 200, code2);
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
