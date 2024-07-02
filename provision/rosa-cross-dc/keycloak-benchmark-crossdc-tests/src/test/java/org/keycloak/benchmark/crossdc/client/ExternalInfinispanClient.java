package org.keycloak.benchmark.crossdc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLIENT_SESSION_CACHE_NAME;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.USER_SESSION_CACHE_NAME;

import java.net.Socket;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

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
        builder.security().ssl().sslContext(sslContext).trustManagers(new TrustManager[]{TRUST_ALL_MANAGER}).hostnameVerifier(ACCEPT_ALL_HOSTNAME_VERIFIER);
        return RestClient.forConfiguration(builder.build());
    }

    private static SSLContext createSSLContext() {
        try {
            var trustManagers = new TrustManager[]{TRUST_ALL_MANAGER};
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
    }

    @Override
    public ExternalCache cache(String name) {
        return new ExternalCache(restClient.cache(name), hotRodClient.getCache(name));
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

    public List<String> getSiteView() {
        return serverInfo(restClient)
                .at("sites_view")
                .asJsonList().stream()
                .map(Json::asString)
                .toList();
    }

    public static final X509ExtendedTrustManager TRUST_ALL_MANAGER = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private static final HostnameVerifier ACCEPT_ALL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };
}
