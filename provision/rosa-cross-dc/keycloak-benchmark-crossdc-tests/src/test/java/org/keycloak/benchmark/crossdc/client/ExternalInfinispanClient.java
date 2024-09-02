package org.keycloak.benchmark.crossdc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.keycloak.benchmark.crossdc.util.HttpClientUtils.ACCEPT_ALL_HOSTNAME_VERIFIER;
import static org.keycloak.benchmark.crossdc.util.HttpClientUtils.MOCK_TRUST_MANAGER;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLIENT_SESSION_CACHE_NAME;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.USER_SESSION_CACHE_NAME;

import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.rest.RestCacheClient;
import org.infinispan.client.rest.RestClient;
import org.infinispan.client.rest.RestResponse;
import org.infinispan.client.rest.configuration.RestClientConfigurationBuilder;
import org.infinispan.commons.dataconversion.internal.Json;
import org.infinispan.commons.util.concurrent.CompletionStages;
import org.keycloak.marshalling.KeycloakModelSchema;

public class ExternalInfinispanClient implements InfinispanClient<InfinispanClient.ExternalCache> {
    private final RestClient restClient;
    private final RemoteCacheManager hotRodClient;
    private final String siteName;

    public ExternalInfinispanClient(String infinispanUrl, String username, String password) {
        var uri = URI.create(Objects.requireNonNull(infinispanUrl));
        var host = uri.getHost();
        var port = uri.getPort() == -1 ? 443 : uri.getPort();
        var sslContext = createSSLContext();
        restClient = createRestClient(host, port, username, password, sslContext);
        hotRodClient = createHotRodClient(host, port, username, password, sslContext);
        siteName = serverInfo(restClient).at("local_site").asString();
    }

    private static RemoteCacheManager createHotRodClient(String host, int port, String username, String password, SSLContext sslContext) {
        var builder = new ConfigurationBuilder();
        builder.addServer().host(host).port(port);
        builder.clientIntelligence(ClientIntelligence.BASIC);
        builder.security().authentication().username(username).password(password);
        builder.security().ssl().enable().sslContext(sslContext).hostnameValidation(false);
        builder.addContextInitializer(KeycloakModelSchema.INSTANCE);
        return new RemoteCacheManager(builder.build());
    }

    private static RestClient createRestClient(String host, int port, String username, String password, SSLContext sslContext) {
        var builder = new RestClientConfigurationBuilder();
        builder.addServer().host(host).port(port);
        builder.security().authentication().username(Objects.requireNonNull(username)).password(Objects.requireNonNull(password));
        builder.security().ssl().sslContext(sslContext).trustManagers(new TrustManager[]{MOCK_TRUST_MANAGER}).hostnameVerifier(ACCEPT_ALL_HOSTNAME_VERIFIER);
        return RestClient.forConfiguration(builder.build());
    }

    private static SSLContext createSSLContext() {
        try {
            var trustManagers = new TrustManager[]{MOCK_TRUST_MANAGER};
            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, null);
            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Json serverInfo(RestClient client) {
        try (var rsp = awaitAndCheckOkStatus(client.container().info())) {
            return Json.read(rsp.body());
        }
    }

    private static RestResponse awaitAndCheckOkStatus(CompletionStage<RestResponse> future) {
        var rsp = CompletionStages.join(future);
        assertEquals(RestResponse.OK, rsp.status());
        return rsp;
    }

    public String siteName() {
        return siteName;
    }

    private record NonExistingCache(String cacheName) implements InfinispanClient.ExternalCache {


        @Override
        public void takeOffline(String backupSiteName) {
            //no-op
        }

        @Override
        public void bringOnline(String backupSiteName) {
            //no-op
        }

        @Override
        public boolean isBackupOnline(String backupSiteName) {
            return false;
        }

        @Override
        public RemoteCache getRemoteCache() {
            return null;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public void clear() {

        }

        @Override
        public boolean contains(String key) {
            return false;
        }

        @Override
        public boolean remove(String key) {
            return false;
        }

        @Override
        public Set<String> keys() {
            return Set.of();
        }

        @Override
        public String name() {
            return cacheName;
        }
    }

    public static class ExternalCache implements InfinispanClient.ExternalCache {

        private final RestCacheClient cacheRestClient;
        private final RemoteCache<Object, Object> cacheHotRodClient;

        private ExternalCache(RestCacheClient cacheRestClient, RemoteCache<Object, Object> cacheHotRodClient) {
            this.cacheRestClient = cacheRestClient;
            this.cacheHotRodClient = cacheHotRodClient;
        }

        @Override
        public long size() {
            var size = cacheHotRodClient.size();
            if (USER_SESSION_CACHE_NAME.equals(cacheRestClient.name()) || CLIENT_SESSION_CACHE_NAME.equals(cacheRestClient.name())) {
                size -= KeycloakClient.getCurrentlyInitializedAdminClients();
                }
            return size;
        }

        @Override
        public void clear() {
            cacheHotRodClient.clear();
        }

        @Override
        public boolean contains(String key) {
            assertEquals(USER_SESSION_CACHE_NAME, cacheHotRodClient.getName());
            return cacheHotRodClient.containsKey(key);
        }

        @Override
        public boolean remove(String key) {
            cacheHotRodClient.withFlags(Flag.SKIP_LISTENER_NOTIFICATION).remove(key);
            return true;
        }

        @Override
        public Set<String> keys() {
            var keySet = cacheHotRodClient.keySet().stream().map(String::valueOf).collect(Collectors.toSet());
            return USER_SESSION_CACHE_NAME.equals(cacheHotRodClient.getName()) ?
                    KeycloakClient.removeAdminClientSessions(keySet) :
                    keySet;
        }

        @Override
        public String name() {
            return cacheHotRodClient.getName();
        }

        @Override
        public void takeOffline(String backupSiteName) {
            awaitAndCheckOkStatus(cacheRestClient.takeSiteOffline(backupSiteName)).close();
        }

        @Override
        public void bringOnline(String backupSiteName) {
            awaitAndCheckOkStatus(cacheRestClient.bringSiteOnline(backupSiteName)).close();
        }

        @Override
        public boolean isBackupOnline(String backupSiteName) {
            try (var rsp = awaitAndCheckOkStatus(cacheRestClient.xsiteBackups())) {
                var status = Json.read(rsp.body()).at(backupSiteName).at("status").asString();
                return "online".equals(status);
            }
        }

        @Override
        public RemoteCache getRemoteCache() {
            return cacheHotRodClient;
        }
    }

    @Override
    public InfinispanClient.ExternalCache cache(String name) {
        RemoteCache<Object, Object> cache = hotRodClient.getCache(name);
        return cache == null ? new NonExistingCache(name) : new ExternalCache(restClient.cache(name), hotRodClient.getCache(name));
    }

    @Override
    public void close() {
        hotRodClient.close();
        try {
            restClient.close();
        } catch (Exception e) {
            //ignored
        }

    }

    public Set<String> getSiteView() {
        return serverInfo(restClient)
                .at("sites_view")
                .asJsonList().stream()
                .map(Json::asString)
                .collect(Collectors.toSet());
    }

    public boolean isSiteOffline(String site) {
        try (var rsp = awaitAndCheckOkStatus(restClient.container().backupStatus(site))) {
            var json = Json.read(rsp.body());
            return json.at("status").asString().equals("offline");
        }
    }

    public void bringBackupOnline(String site) {
        try (var ignore = awaitAndCheckOkStatus(restClient.container().bringBackupOnline(site))) {}
    }
}
