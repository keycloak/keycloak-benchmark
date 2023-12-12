package org.keycloak.benchmark.crossdc.client;

import org.apache.http.client.utils.URIBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.keycloak.benchmark.crossdc.AbstractCrossDCTest.ISPN_USERNAME;
import static org.keycloak.benchmark.crossdc.AbstractCrossDCTest.MAIN_PASSWORD;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.getBasicAuthenticationHeader;

public class ExternalInfinispanClient implements InfinispanClient {
    private final HttpClient httpClient;
    private final String infinispanUrl;
    private final String username;
    private final String password;

    Pattern UUID_REGEX = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public ExternalInfinispanClient(HttpClient httpClient, String infinispanUrl, String username, String password) {
        this.httpClient = httpClient;
        this.infinispanUrl = infinispanUrl;
        this.username = username;
        this.password = password;
    }

    public class ExternalCache implements InfinispanClient.Cache {

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

                return Long.parseLong(response.body()) - KeycloakClient.getCurrentlyInitializedAdminClients();
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
        public boolean contains(String key) {
            return keys().contains(key);
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

                return KeycloakClient.removeAdminClientSessions(Arrays.stream(response.body().split(","))
                        .map(UUID_REGEX::matcher)
                        .map(m -> {
                            if (m.find()) {
                                return m.group();
                            } else {
                                return null;
                            }
                        }).filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public Cache cache(String name) {
        return new ExternalCache(name);
    }
}
