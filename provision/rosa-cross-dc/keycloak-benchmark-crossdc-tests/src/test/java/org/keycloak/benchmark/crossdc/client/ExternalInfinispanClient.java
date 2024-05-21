package org.keycloak.benchmark.crossdc.client;

import org.apache.http.client.utils.URIBuilder;
import org.keycloak.benchmark.crossdc.util.InfinispanUtils;
import org.keycloak.util.JsonSerialization;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.keycloak.benchmark.crossdc.AbstractCrossDCTest.ISPN_USERNAME;
import static org.keycloak.benchmark.crossdc.AbstractCrossDCTest.MAIN_PASSWORD;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.getBasicAuthenticationHeader;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.getNestedValue;

public class ExternalInfinispanClient implements InfinispanClient<InfinispanClient.ExternalCache> {
    private final HttpClient httpClient;
    private final String infinispanUrl;
    private final String username;
    private final String password;
    private final String keycloakServerURL;
    private final String siteName;

    Pattern UUID_REGEX = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public ExternalInfinispanClient(HttpClient httpClient, String infinispanUrl, String username, String password, String keycloakServerURL) {
        assertNotNull(infinispanUrl, "Infinispan URL cannot be null");
        this.httpClient = httpClient;
        this.infinispanUrl = infinispanUrl;
        this.username = username;
        this.password = password;
        this.keycloakServerURL = keycloakServerURL;

        HttpResponse<String> stringHttpResponse = sendRequestWithAction(infinispanUrl + "/rest/v2/cache-managers/default", "GET", null);
        assertEquals(200, stringHttpResponse.statusCode());

        Map<String, Object> returnedValues;
        try {
            returnedValues = JsonSerialization.readValue(stringHttpResponse.body(), Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.siteName = (String) returnedValues.get("local_site");
    }

    public String siteName() {
        return siteName;
    }

    public class ExternalCache implements InfinispanClient.ExternalCache {

        private final String cacheName;

        private ExternalCache(String cacheName) {
            this.cacheName = cacheName;
        }

        @Override
        public long size() {
            URI uri = null;
            try {
                uri = new URIBuilder(infinispanUrl + "/rest/v2/caches/" + cacheName + "/")
                        .addParameter("action", "size")
                        .build();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", getBasicAuthenticationHeader(ISPN_USERNAME, MAIN_PASSWORD))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());

                if (cacheName.equals(InfinispanUtils.SESSIONS) || cacheName.equals(InfinispanUtils.CLIENT_SESSIONS)) {
                    return Long.parseLong(response.body()) - KeycloakClient.getCurrentlyInitializedAdminClients();
                }
                return Long.parseLong(response.body());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void clear() {
            URI uri = null;
            try {
                uri = new URIBuilder(infinispanUrl + "/rest/v2/caches/" + cacheName + "/")
                        .addParameter("action","clear")
                        .build();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9")
                    .header("Authorization", getBasicAuthenticationHeader(username, password))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = null;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Exception thrown for cache " + cacheName, e);
            }

            assertEquals(204, response.statusCode());
        }

        @Override
        public boolean contains(String key) throws URISyntaxException, IOException, InterruptedException {
            URI uri = new URIBuilder( keycloakServerURL + "/realms/master/remote-cache/" + cacheName + "/contains/" + key).build();
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
                uri = new URIBuilder( keycloakServerURL + "/realms/master/remote-cache/" + cacheName + "/remove/" + key + "?skipListeners=true").build();
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
            URI uri = null;
            try {
                uri = new URIBuilder(infinispanUrl + "/rest/v2/caches/" + cacheName + "/")
                        .addParameter("action", "keys")
                        .build();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", getBasicAuthenticationHeader(ISPN_USERNAME, MAIN_PASSWORD))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());

                Set<String> keys = Arrays.stream(response.body().split(","))
                        .map(UUID_REGEX::matcher)
                        .map(m -> {
                            if (m.find()) {
                                return m.group();
                            } else {
                                return null;
                            }
                        }).filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (cacheName.equals(InfinispanUtils.SESSIONS)) {
                    return KeycloakClient.removeAdminClientSessions(keys);
                }

                return keys;
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void takeOffline(String backupSiteName) {
            HttpResponse<String> stringHttpResponse = sendRequestWithAction(infinispanUrl + "/rest/v2/caches/" + cacheName + "/x-site/backups/" + backupSiteName, "POST", "take-offline");
            assertEquals(200, stringHttpResponse.statusCode());
        }

        @Override
        public void bringOnline(String backupSiteName) {
            HttpResponse<String> stringHttpResponse = sendRequestWithAction(infinispanUrl + "/rest/v2/caches/" + cacheName + "/x-site/backups/" + backupSiteName, "POST", "bring-online");
            assertEquals(200, stringHttpResponse.statusCode());
        }

        @Override
        public boolean isBackupOnline(String backupSiteName) throws IOException {
            String response = sendRequestWithAction(infinispanUrl + "/rest/v2/caches/" + cacheName + "/x-site/backups/", "GET", null).body();
            Map<String, Object> returnedValues = JsonSerialization.readValue(response, Map.class);

            String status = getNestedValue(returnedValues, backupSiteName, "status");
            return "online".equals(status);
        }
    }

    private HttpResponse<String> sendRequestWithAction(String url, String method, String action) {
        URI uri = null;
        try {
            var uriBuilder = new URIBuilder(url);

            if (action != null) {
                uriBuilder.addParameter("action", action);
            }

            uri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        var builder = HttpRequest.newBuilder().uri(uri);

        if (method.equals("POST")) {
            builder = builder.method("POST", HttpRequest.BodyPublishers.noBody());
        }

        HttpRequest request = builder.header("Authorization", getBasicAuthenticationHeader(ISPN_USERNAME, MAIN_PASSWORD))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ExternalCache cache(String name) {
        return new ExternalCache(name);
    }
}
