package org.keycloak.benchmark.crossdc.client;

import org.apache.http.client.utils.URIBuilder;
import org.jboss.resteasy.spi.NotImplementedYetException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.benchmark.crossdc.AbstractCrossDCTest;
import org.keycloak.benchmark.crossdc.util.KeycloakUtils;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.getNestedValue;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.getFormDataAsString;

public class KeycloakClient {
    private final HttpClient httpClient;
    private final String keycloakServerUrl;
    private static final Map<KeycloakClient, Keycloak> adminClients = new ConcurrentHashMap<>();

    public KeycloakClient(HttpClient httpClient, String keycloakServerUrl) {
        this.httpClient = httpClient;
        this.keycloakServerUrl = keycloakServerUrl;
        reconnectAdminClient();
    }

    public static int getCurrentlyInitializedAdminClients() {
        return (int) adminClients.values().stream().filter(adminClient -> adminClient.tokenManager().getAccessToken() != null).count();
    }

    public static Set<String> removeAdminClientSessions(Set<String> sessionIds) {
        Set<String> adminClientSessionIds = adminClients.values().stream()
                .filter(adminClient -> adminClient.tokenManager().getAccessToken() != null)
                .map(adminClient -> adminClient.tokenManager().getAccessToken().getSessionState())
                .collect(Collectors.toSet());

        sessionIds.removeIf(adminClientSessionIds::contains);
        return sessionIds;
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
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "authorization_code");
        formData.put("client_id", clientId);
        formData.put("client_secret", clientSecret);
        formData.put("redirect_uri", testRealmUrl(realmName) + "/account");
        formData.put("code", code);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(testRealmUrl(realmName) + "/protocol/openid-connect/token"))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedReturnCode, response.statusCode());

        return JsonSerialization.readValue(response.body(), Map.class);
    }

    public String usernamePasswordLogin(String realmName, String username, String password, String clientId) throws IOException, URISyntaxException, InterruptedException {
        String formUrl = openLoginForm(realmName, clientId);
        Map<String, String> formData = new HashMap<>();
        formData.put("username", username);
        formData.put("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(formUrl))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // first redirect
        String location = response.headers().firstValue("Location").orElse(null);
        assertNotNull(location);
        assertEquals(302, response.statusCode());

        // capture code parameter
        String code = location.substring(location.indexOf("code=") + 5, location.length());

        // follow the redirect
        request = HttpRequest.newBuilder()
                .uri(new URI(location))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // second redirect
        location = response.headers().firstValue("Location").orElse(null);
        assertNotNull(location);
        assertEquals(302, response.statusCode());

        // follow the second redirect
        request = HttpRequest.newBuilder()
                .uri(new URI(location))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // we landed at account page
        assertTrue(response.body().contains("Welcome to Keycloak account management"));
        assertEquals(200, response.statusCode());

        return code;
    }

    public String openLoginForm(String realmName, String clientId) throws IOException, InterruptedException, URISyntaxException {
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

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Failed to open login form for realm " + realmName + " with response body " + response.body());

        Pattern pattern = Pattern.compile("action=\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(response.body());
        if (matcher.find()) {
            return matcher.group(1).replaceAll("&amp;", "&");
        }

        return null;
    }

    public InfinispanClient embeddedIspn() {
        return new InfinispanClient() {
            @Override
            public Cache cache(String name) {
                return new Cache() {
                    @Override
                    public long size() {
                        try {
                            return ((Integer) getNestedValue(getEmbeddedISPNstats(),"cacheSizes", "sessions")).longValue() - KeycloakClient.getCurrentlyInitializedAdminClients();
                        } catch (URISyntaxException | IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void clear() {
                        throw new NotImplementedYetException("This is not yet implemented :/");
                    }

                    @Override
                    public boolean contains(String key) {
                        throw new NotImplementedYetException("This is not yet implemented :/");
                    }

                    @Override
                    public Set<String> keys() {
                        throw new NotImplementedYetException("This is not yet implemented :/");
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
        return KeycloakBuilder.builder()
                .serverUrl(keycloakServerUrl)
                .clientId("admin-cli")
                .username("admin")
                .password(AbstractCrossDCTest.MAIN_PASSWORD)
                .realm("master")
                .resteasyClient(KeycloakUtils.newResteasyClientBuilder().build())
                .build();
    }

    public void reconnectAdminClient() {
        Keycloak adminClient = adminClients.get(this);
        if (adminClient != null && !adminClient.isClosed()) {
            adminClient.close();
        }

        adminClients.put(this, createAdminClient(this));
    }
}
