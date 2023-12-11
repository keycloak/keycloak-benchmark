package org.keycloak.benchmark.crossdc;

import org.apache.http.client.utils.URIBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.keycloak.util.JsonSerialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class CrossDCTest {
    protected static final int CLIENT_COUNT = 1;
    protected static final int USER_COUNT = 1000;
    private static String ISPN_USERNAME = System.getProperty("infinispan.username", "developer");;
    protected static final String ISPN_PASSWORD = System.getProperty("infinispan.password");

    protected static final DatacenterInfo dc1, dc2;

    static {
        dc1 = new DatacenterInfo(System.getProperty("keycloak.dc1.url"), "realm-0", System.getProperty("infinispan.dc1.url"), System.getProperty("load-balancer.url"));
        dc2 = new DatacenterInfo(System.getProperty("keycloak.dc2.url"), "realm-0", System.getProperty("infinispan.dc2.url"), System.getProperty("load-balancer.url"));
    }

    static HttpClient httpClient;
    static CookieHandler cookieHandler = new CookieManager();

    private static final TrustManager MOCK_TRUST_MANAGER = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    static {
        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, new TrustManager[]{ MOCK_TRUST_MANAGER }, new SecureRandom());

            httpClient =  HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .cookieHandler(cookieHandler)
                    .version(HttpClient.Version.HTTP_2)
                    .build();
        } catch (NoSuchAlgorithmException e) {
        } catch (KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

    @BeforeAll
    public static void createDataset() throws URISyntaxException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(dc1.getDatasetLastClientURL()))
                .GET()
                .build();
        String lastClient = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();

        request = HttpRequest.newBuilder()
                .uri(new URI(dc1.getDatasetLastUserURL()))
                .GET()
                .build();
        String lastUser = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();

        // feed the DB with data if there is no required number of clients and users
        if (!(lastClient.contains("client-" + (CLIENT_COUNT - 1)) && lastUser.contains("user-" + (USER_COUNT - 1)))) {
            URI uri = new URIBuilder(dc1.getLoadBalancerURL() + "/realms/master/dataset/create-realms")
                    .addParameter("realm-name", dc1.getTestRealm())
                    .addParameter("count", String.valueOf(1))
                    .addParameter("threads-count", String.valueOf(1))
                    .addParameter("clients-per-realm", String.valueOf(CLIENT_COUNT))
                    .addParameter("users-per-realm", String.valueOf(USER_COUNT))
                    .build();
            request = HttpRequest.newBuilder(uri)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(response.body().contains("Task started successfully"));

            request = HttpRequest.newBuilder()
                    .uri(new URI(dc1.getDatasetStatusURL()))
                    .GET()
                    .build();

            while (!httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body().contains("No task in progress.")) {
                Thread.sleep(500);
            }
        }
    }

    @Test
    public void loginLogoutTest() throws URISyntaxException, IOException, InterruptedException {
        //Clear the Session Cache data from both data centers
        clearCacheData(dc1.getInfinispanServerURL(),"sessions");
        clearCacheData(dc2.getInfinispanServerURL(),"sessions");

        //Login and exchange code in DC1
        String code = usernamePasswordLogin(dc1, "user-1", "user-1-password");
        Map<String, Object> tokensMap = exchangeCode(dc1, code, "client-0", "client-0-secret", 200);

        //Making sure the code cannot be reused in any of the DCs
        exchangeCode(dc2, code, "client-0", "client-0-secret", 400);
        exchangeCode(dc1, code, "client-0", "client-0-secret", 400);

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC1
        HttpResponse verifySessionKeyResponseInDC1 = getISPNStats(dc1, "sessions", "keys");
        assertTrue(verifySessionKeyResponseInDC1.body().toString().contains(code.toString().split("[.]")[1]));
        //Verify session cache size in external ISPN DC1
        HttpResponse ispnDC1Response = getISPNStats(dc1, "sessions", "size");
        assertEquals(200, ispnDC1Response.statusCode());
        assertEquals(1,Integer.parseInt(ispnDC1Response.body().toString()));
        //Verify session cache size in embedded ISPN DC1
        assertEquals(1, (Integer) getNestedValue(getEmbeddedISPNstats(dc1),"cacheSizes","sessions"));

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC2
        HttpResponse verifySessionKeyResponseInDC2 = getISPNStats(dc2, "sessions", "keys");
        assertTrue(verifySessionKeyResponseInDC2.body().toString().contains(code.toString().split("[.]")[1]));
        //Verify session cache size in external ISPN DC2
        HttpResponse ispnDC2Response = getISPNStats(dc2, "sessions", "size");
        assertEquals(200, ispnDC2Response.statusCode());
        assertEquals(1,Integer.parseInt(ispnDC2Response.body().toString()));
        //Verify session cache size in embedded ISPN DC2
        assertEquals(1, (Integer) getNestedValue(getEmbeddedISPNstats(dc2),"cacheSizes","sessions"));

        //Logout from DC1
        logout(dc1, (String) tokensMap.get("id_token"), "client-0");

        //Verify session cache size in external ISPN DC1 post logout
        HttpResponse ispnDC1ResponseAfterLogout = getISPNStats(dc1, "sessions", "size");
        assertEquals(200, ispnDC1ResponseAfterLogout.statusCode());
        assertEquals(0,Integer.parseInt(ispnDC1ResponseAfterLogout.body().toString()));
        //Verify session cache size in embedded ISPN DC1 post logout
        assertEquals(0, (Integer) getNestedValue(getEmbeddedISPNstats(dc1),"cacheSizes","sessions"));
        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC1
        HttpResponse verifySessionKeyResponseInDC1PostLogout = getISPNStats(dc1, "sessions", "keys");
        assertFalse(verifySessionKeyResponseInDC1PostLogout.body().toString().contains(code.toString().split("[.]")[1]));

        //Verify session cache size in external ISPN  DC2 post logout
        HttpResponse ispnDC2ResponseAfterLogout = getISPNStats(dc2, "sessions", "size");
        assertEquals(200, ispnDC2ResponseAfterLogout.statusCode());
        assertEquals(0,Integer.parseInt(ispnDC2ResponseAfterLogout.body().toString()));
        //Verify session cache size in embedded ISPN DC2
        assertEquals(0, (Integer) getNestedValue(getEmbeddedISPNstats(dc2),"cacheSizes","sessions"));
        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC1
        HttpResponse verifySessionKeyResponseInDC2PostLogout = getISPNStats(dc2, "sessions", "keys");
        assertFalse(verifySessionKeyResponseInDC2PostLogout.body().toString().contains(code.toString().split("[.]")[1]));
    }

    @Test
    public void keycloakEntityReplicationOverCacheTest() throws URISyntaxException, IOException, InterruptedException {
        //ToDo need to extract the user count from a better place than the lastEntityUrl, thus reducing the reliance on dataset provider

        String key = "status";
        String patternString = "\"" + key + "\":\"([^\"]+)\"";
        Pattern pattern = Pattern.compile(patternString);

        HttpRequest lastClientDC1Request = HttpRequest.newBuilder()
                .uri(new URI(dc1.getDatasetLastClientURL()))
                .GET()
                .build();
        int lastClientBeforeCreate = getMatchingRegexFromLastEntityCreated(pattern,
                httpClient.send(lastClientDC1Request, HttpResponse.BodyHandlers.ofString()).body());

        HttpRequest lastUserDC1Request = HttpRequest.newBuilder()
                .uri(new URI(dc1.getDatasetLastUserURL()))
                .GET()
                .build();
        int lastUserBeforeCreate = getMatchingRegexFromLastEntityCreated(pattern,
                httpClient.send(lastUserDC1Request, HttpResponse.BodyHandlers.ofString()).body());

        createEntities("users", "1");
        createEntities("clients", "1");

        int lastClientAfterCreateDC1 = getMatchingRegexFromLastEntityCreated(pattern,
                httpClient.send(lastClientDC1Request, HttpResponse.BodyHandlers.ofString()).body());
        int lastUserAfterCreateDC1 = getMatchingRegexFromLastEntityCreated(pattern,
                httpClient.send(lastUserDC1Request, HttpResponse.BodyHandlers.ofString()).body());

        assertTrue(lastClientAfterCreateDC1 == (lastClientBeforeCreate + 1));
        assertTrue(lastUserAfterCreateDC1 == (lastUserBeforeCreate + 1));

        HttpRequest lastClientDC2Request = HttpRequest.newBuilder()
                .uri(new URI(dc2.getDatasetLastClientURL()))
                .GET()
                .build();

        HttpRequest lastUserDC2Request = HttpRequest.newBuilder()
                .uri(new URI(dc2.getDatasetLastUserURL()))
                .GET()
                .build();

        int lastClientAfterCreateDC2 = getMatchingRegexFromLastEntityCreated(pattern,
                httpClient.send(lastClientDC2Request, HttpResponse.BodyHandlers.ofString()).body());
        int lastUserAfterCreateDC2 = getMatchingRegexFromLastEntityCreated(pattern,
                httpClient.send(lastUserDC2Request, HttpResponse.BodyHandlers.ofString()).body());

        assertEquals(lastClientAfterCreateDC1,lastClientAfterCreateDC2);
        assertEquals(lastUserAfterCreateDC1,lastUserAfterCreateDC2);
    }

    private void createEntities(String entityName, String entityCount) throws URISyntaxException, IOException, InterruptedException {

        URI uri = new URIBuilder((dc1.getDatasetCreateURL()+entityName))
                .addParameter("count", entityCount)
                .addParameter("realm-name", dc1.getTestRealm())
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("Task started successfully"));

        request = HttpRequest.newBuilder()
                .uri(new URI(dc1.getDatasetStatusURL()))
                .GET()
                .build();

        while (!httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body().contains("No task in progress.")) {
            Thread.sleep(500);
        }
    }

    private void clearCacheData(String ispnServerRestEndpoint, String cacheName) throws URISyntaxException, IOException, InterruptedException {

        URI uri = new URIBuilder(ispnServerRestEndpoint + "/rest/v2/caches/" + cacheName + "/")
                .addParameter("action","clear")
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9")
                .header("Authorization", getBasicAuthenticationHeader(ISPN_USERNAME, ISPN_PASSWORD))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
    }

    private HttpResponse listenISPNEvents(DatacenterInfo dc, String cacheName) throws URISyntaxException, IOException, InterruptedException {

        URI uri = new URIBuilder(dc.getInfinispanServerURL() + "/rest/v2/caches/" + cacheName + "/")
                .addParameter("action", "listen")
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept","text/plain,application/json")
                .header("Authorization", getBasicAuthenticationHeader(ISPN_USERNAME, ISPN_PASSWORD))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode());
        return response;
    }

    private HttpResponse getISPNStats(DatacenterInfo dc, String cacheName, String cacheAction) throws URISyntaxException, IOException, InterruptedException {

        URI uri = new URIBuilder(dc.getInfinispanServerURL() + "/rest/v2/caches/" + cacheName + "/")
                .addParameter("action", cacheAction)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", getBasicAuthenticationHeader(ISPN_USERNAME, ISPN_PASSWORD))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        return response;
    }

    private Map<String, Object> getEmbeddedISPNstats(DatacenterInfo dc) throws URISyntaxException, IOException, InterruptedException {
        URI uri = new URIBuilder(dc.getDatasetCacheStatsURL()).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> caches = JsonSerialization.readValue(response.body(), Map.class);
        return caches;

    }
    public static <T> T getNestedValue(Map map, String... keys) {
        Object value = map;
        for (String key : keys) {
            value = ((Map) value).get(key);
        }
        return (T) value;
    }

    public static int getMatchingRegexFromLastEntityCreated(Pattern pattern, String lastEntityJson) {
        //System.out.println(lastEntityJson);
        Matcher matcher = pattern.matcher(lastEntityJson);
        Pattern numberPattern = Pattern.compile("\\d+");
        int entityNumber = 0;
        if (matcher.find()) {
            String value = matcher.group(1);
            // Extract the number from the value
            Matcher numberMatcher = numberPattern.matcher(value);

            if (numberMatcher.find()) {
                entityNumber = Integer.parseInt(numberMatcher.group());
                //System.out.println("Extracted number: " + entityNumber);
            } else {
                System.out.println("No number found in the value.");
            }
        } else {
            System.out.println("Key not found in JSON.");
        }

        return entityNumber;
    }
    private void logout(DatacenterInfo dc, String idToken, String clientId) throws URISyntaxException, IOException, InterruptedException {
        URI uri = new URIBuilder(new URI(dc.getLogoutEndpoint()))
                .addParameter("post_logout_redirect_uri", dc.getRedirectURI())
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
        assertEquals(dc.getRedirectURI(), location);
    }

    private Map<String, Object> exchangeCode(DatacenterInfo dc, String code, String clientId, String clientSecret, int expectedReturnCode) throws URISyntaxException, IOException, InterruptedException {
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "authorization_code");
        formData.put("client_id", clientId);
        formData.put("client_secret", clientSecret);
        formData.put("redirect_uri", dc.getRedirectURI());
        formData.put("code", code);

        HttpRequest request = HttpRequest.newBuilder()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .uri(new URI(dc.getTokenEndpoint()))
                .POST(HttpRequest.BodyPublishers.ofString(getFormDataAsString(formData)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedReturnCode, response.statusCode());

        return JsonSerialization.readValue(response.body(), Map.class);
    }

    private String usernamePasswordLogin(DatacenterInfo dc, String username, String password) throws IOException, URISyntaxException, InterruptedException {
        String formUrl = openLoginForm(dc, "client-0");
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

    private String openLoginForm(DatacenterInfo dc, String clientId) throws IOException, InterruptedException, URISyntaxException {
        URI uri = new URIBuilder(dc.getLoginEndpoint())
                .addParameter("response_type", "code")
                .addParameter("scope", "openid")
                .addParameter("state", UUID.randomUUID().toString())
                .addParameter("redirect_uri", dc.getRedirectURI())
                .addParameter("client_id", clientId)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        Pattern pattern = Pattern.compile("action=\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(response.body());
        if (matcher.find()) {
            return matcher.group(1).replaceAll("&amp;", "&");
        }

        return null;
    }

    private static String getFormDataAsString(Map<String, String> formData) {
        StringBuilder formBodyBuilder = new StringBuilder();
        for (Map.Entry<String, String> singleEntry : formData.entrySet()) {
            if (formBodyBuilder.length() > 0) {
                formBodyBuilder.append("&");
            }
            formBodyBuilder.append(URLEncoder.encode(singleEntry.getKey(), StandardCharsets.UTF_8));
            formBodyBuilder.append("=");
            formBodyBuilder.append(URLEncoder.encode(singleEntry.getValue(), StandardCharsets.UTF_8));
        }
        return formBodyBuilder.toString();
    }
}
