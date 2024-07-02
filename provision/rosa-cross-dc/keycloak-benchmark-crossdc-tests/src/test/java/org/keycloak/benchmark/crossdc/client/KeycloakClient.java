package org.keycloak.benchmark.crossdc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.getNestedValue;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.URIToHostString;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.extractCodeFromResponse;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.getFormDataAsString;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.getLoginFormActionURL;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.pointsToSameIp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.http.client.utils.URIBuilder;
import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.NotImplementedYetException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.benchmark.crossdc.AbstractCrossDCTest;
import org.keycloak.benchmark.crossdc.util.HttpClientUtils;
import org.keycloak.benchmark.crossdc.util.KeycloakUtils;
import org.keycloak.common.util.Time;
import org.keycloak.util.JsonSerialization;

public class KeycloakClient {
    private final HttpClient httpClient;
    private final String keycloakServerUrl;
    private final String keycloakDownURL;
    private final String keycloakUpURL;

    private final boolean activePassive;
    private static final Map<KeycloakClient, Keycloak> adminClients = new ConcurrentHashMap<>();
    private static final Logger LOG = Logger.getLogger(KeycloakClient.class);

    public KeycloakClient(HttpClient httpClient, String keycloakServerUrl, boolean activePassive) {
        assertNotNull(keycloakServerUrl, "Keycloak server URL must not be null.");

        this.httpClient = httpClient;
        this.keycloakServerUrl = keycloakServerUrl;
        this.activePassive = activePassive;

        this.keycloakDownURL = keycloakServerUrl + "/realms/master/dataset/take-dc-down";
        this.keycloakUpURL = keycloakServerUrl + "/realms/master/dataset/take-dc-up";
    }

    public static int getCurrentlyInitializedAdminClients() {
        return adminClients.size();
    }

    public static Set<String> removeAdminClientSessions(Set<String> sessionIds) {
        Set<String> adminClientSessionIds = adminClients.values().stream()
                .filter(adminClient -> adminClient.tokenManager().getAccessToken() != null)
                .map(adminClient -> adminClient.tokenManager().getAccessToken().getSessionState())
                .collect(Collectors.toSet());

        sessionIds.removeIf(adminClientSessionIds::contains);
        return sessionIds;
    }

    public static void cleanAdminClients() {
        adminClients.values().forEach(Keycloak::close);
        adminClients.clear();
    }

    public void logout(String realmName, String idToken, String clientId) throws URISyntaxException, IOException, InterruptedException {
        URI uri = new URIBuilder(new URI(testRealmUrl(realmName) + "/protocol/openid-connect/logout"))
                .addParameter("post_logout_redirect_uri", testRealmUrl(realmName) + "/account")
                .addParameter("client_id", clientId)
                .addParameter("id_token_hint", idToken)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(302, response.statusCode());
        String location = response.headers().firstValue("Location").orElse(null);
        assertNotNull(location);
        assertEquals(testRealmUrl(realmName) + "/account", location);
    }

    public Map<String, Object> exchangeCode(String realmName, String clientId, String clientSecret, int expectedReturnCode, String code) throws URISyntaxException, IOException, InterruptedException {
        return exchangeCode(realmName, clientId, clientSecret, expectedReturnCode, code, getRedirectUri(realmName));
    }

    public Map<String, Object> exchangeCode(String realmName, String clientId, String clientSecret, int expectedReturnCode, String code, String redirectUri) throws URISyntaxException, IOException, InterruptedException {
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "authorization_code");
        formData.put("client_id", clientId);
        formData.put("client_secret", clientSecret);
        formData.put("redirect_uri", redirectUri);
        formData.put("code", code);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(testRealmUrl(realmName) + "/protocol/openid-connect/token"))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedReturnCode, response.statusCode(), "Expected return code was " + expectedReturnCode + " but was " + response.statusCode() + " with response body " + response.body());

        return JsonSerialization.readValue(response.body(), Map.class);
    }

    public Map<String, Object> passwordGrant(String realmName, String clientId, String username, String password) throws URISyntaxException, IOException, InterruptedException {
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "password");
        formData.put("username", username);
        formData.put("password", password);
        formData.put("client_id", clientId);
        formData.put("scope", "openid profile");

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(testRealmUrl(realmName) + "/protocol/openid-connect/token"))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonSerialization.readValue(response.body(), Map.class);
    }

    public HttpResponse<String> revokeRefreshToken(String realmName, String clientId, String refreshToken) throws URISyntaxException, IOException, InterruptedException {
        Map<String, String> formData = new HashMap<>();
        formData.put("client_id", clientId);
        formData.put("token", refreshToken);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(testRealmUrl(realmName) + "/protocol/openid-connect/revoke"))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public Map<String, Object> refreshToken(String realmName, String refreshToken, String clientId, String clientSecret, int expectedReturnCode) throws URISyntaxException, IOException, InterruptedException {
        HttpResponse<String> response = refreshToken(realmName, refreshToken, clientId, clientSecret);
        assertEquals(expectedReturnCode, response.statusCode());

        return JsonSerialization.readValue(response.body(), Map.class);
    }

    public HttpResponse<String> refreshToken(String realmName, String refreshToken, String clientId, String clientSecret) throws URISyntaxException, IOException, InterruptedException {
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "refresh_token");
        formData.put("refresh_token", refreshToken);
        formData.put("client_id", clientId);
        formData.put("client_secret", clientSecret);
        formData.put("redirect_uri", testRealmUrl(realmName) + "/account");

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(testRealmUrl(realmName) + "/protocol/openid-connect/token"))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public String usernamePasswordLogin(String realmName, String username, String password, String clientId) throws IOException, URISyntaxException, InterruptedException {
        HttpResponse<String> loginFormResponse = openLoginForm(realmName, clientId);
        assertEquals(200, loginFormResponse.statusCode(), "Failed to open login form for realm " + realmName + " with response body " + loginFormResponse.body());
        String formUrl = getLoginFormActionURL(loginFormResponse);

        Map<String, String> formData = new HashMap<>();
        formData.put("username", username);
        formData.put("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(formUrl))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        return extractCodeFromResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    public HttpResponse<String> openLoginForm(String realmName, String clientId) throws IOException, InterruptedException, URISyntaxException {
        URI uri = new URIBuilder(testRealmUrl(realmName) + "/protocol/openid-connect/auth")
                .addParameter("response_type", "code")
                .addParameter("scope", "openid")
                .addParameter("state", UUID.randomUUID().toString())
                .addParameter("redirect_uri", testRealmUrl(realmName) + "/account")
                .addParameter("client_id", clientId)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public void markLBCheckDown() throws URISyntaxException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(keycloakDownURL))
                .GET()
                .build();

        HttpClientUtils.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    public void markLBCheckUp() throws URISyntaxException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(keycloakUpURL))
                .GET()
                .build();

        HttpClientUtils.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }


    public boolean isActive(KeycloakClient loadBalancer) throws UnknownHostException {
        if (!activePassive) return true;

        String loadBalancerHost = URIToHostString(loadBalancer.getKeycloakServerUrl());
        String thisKeycloakHost = URIToHostString(getKeycloakServerUrl());

        return pointsToSameIp(loadBalancerHost, thisKeycloakHost);
    }

    public void waitToBeActive(KeycloakClient loadBalancer) throws UnknownHostException, InterruptedException {
        if (!activePassive) return;

        int startTime = Time.currentTime();
        int timeLimit = startTime + 600; // 10 minutes
        LOG.infof("Waiting for Keycloak %d to be active.", keycloakServerUrl);
        while (!isActive(loadBalancer) && Time.currentTime() <= timeLimit) {
            Thread.sleep(1000);
        }

        if (Time.currentTime() > timeLimit) {
            throw new RuntimeException("Keycloak " + keycloakServerUrl + " did not become active in 10 minutes.");
        }

        LOG.infof("Keycloak %d is active. Took %d seconds.", keycloakServerUrl, Time.currentTime() - startTime);
    }

    public String getKeycloakServerUrl() {
        return keycloakServerUrl;
    }

    public InfinispanClient<InfinispanClient.Cache> embeddedIspn() {
        return new InfinispanClient<>() {
            @Override
            public Cache cache(String name) {
                return new Cache() {
                    @Override
                    public long size() {
                        try {
                            return ((Integer) getNestedValue(getEmbeddedISPNstats(),"cacheSizes", name)).longValue() - KeycloakClient.getCurrentlyInitializedAdminClients();
                        } catch (URISyntaxException | IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void clear() {
                        try {
                            URI uri = new URIBuilder(testRealmUrl("master") + "/cache/" + name + "/clear").build();
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(uri)
                                    .GET()
                                    .build();

                            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                            assertEquals(200, response.statusCode());
                        } catch (URISyntaxException | IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public boolean contains(String key) throws URISyntaxException, IOException, InterruptedException {
                        URI uri = new URIBuilder( testRealmUrl("master") + "/cache/" + name + "/contains/" + key).build();
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(uri)
                                .GET()
                                .build();

                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        return Boolean.parseBoolean(response.body());
                    }

                    @Override
                    public boolean remove(String key) {
                        URI uri = null;
                        try {
                            uri = new URIBuilder( testRealmUrl("master") + "/cache/" + name + "/remove/" + key + "?skipListeners=true").build();
                        } catch (URISyntaxException e) {
                            throw new RuntimeException(e);
                        }

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(uri)
                                .GET()
                                .build();


                        HttpResponse<String> response;
                        try {
                            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        } catch (IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        assertEquals(200, response.statusCode());
                        return Boolean.parseBoolean(response.body());
                    }

                    @Override
                    public Set<String> keys() {
                        throw new NotImplementedYetException("This is not yet implemented :/");
                    }

                    @Override
                    public String name() {
                        return name;
                    }
                };
            }
        };
    }

    private Map<String, Object> getEmbeddedISPNstats() throws URISyntaxException, IOException, InterruptedException {
        URI uri = new URIBuilder( testRealmUrl("master") + "/cache/sizes").build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> caches = JsonSerialization.readValue(response.body(), Map.class);
        return caches;
    }

    private String testRealmUrl(String realmName) {
        return keycloakServerUrl + "/realms/" + realmName;
    }

    public Keycloak adminClient() {
        return adminClients.computeIfAbsent(this, this::createAdminClient);
    }

    private Keycloak createAdminClient(KeycloakClient ignored) {
        // Build new admin client
        Keycloak newAdminClient = KeycloakBuilder.builder()
                .serverUrl(keycloakServerUrl)
                .clientId("admin-cli")
                .username("admin")
                .password(AbstractCrossDCTest.MAIN_PASSWORD)
                .realm("master")
                .resteasyClient(KeycloakUtils.newResteasyClientBuilder().build())
                .build();

        // Initialize access token so all initialized admin clients have a session in Keycloak
        newAdminClient.tokenManager().getAccessToken();

        return newAdminClient;
    }

    public String getRedirectUri(String realmName) {
        return testRealmUrl(realmName) + "/account";
    }
}
