package org.keycloak.benchmark.crossdc;

public class DatacenterInfo {

    private final String keycloakServerURL;
    private final String infinispanServerURL;
    private final String testRealm;

    private final String testRealmURL;
    private final String loginEndpoint;
    private final String logoutEndpoint;
    private final String tokenEndpoint;
    private final String redirectURI;

    private final String datasetStatusURL;
    private final String datasetLastClientURL;
    private final String datasetLastUserURL;
    private final String datasetCacheStatsURL;

    public DatacenterInfo(String keycloakServerURL, String testRealm, String infinispanServerURL) {
        this.keycloakServerURL = keycloakServerURL;
        this.infinispanServerURL = infinispanServerURL;
        this.testRealm = testRealm;

        this.testRealmURL = keycloakServerURL + "/realms/" + testRealm;
        this.loginEndpoint = testRealmURL + "/protocol/openid-connect/auth";
        this.logoutEndpoint = testRealmURL + "/protocol/openid-connect/logout";
        this.tokenEndpoint = testRealmURL + "/protocol/openid-connect/token";
        this.redirectURI = testRealmURL + "/account";

        this.datasetStatusURL = keycloakServerURL + "/realms/master/dataset/status";
        this.datasetLastClientURL = keycloakServerURL + "/realms/master/dataset/last-client?realm-name=" + testRealm;
        this.datasetLastUserURL = keycloakServerURL + "/realms/master/dataset/last-user?realm-name=" + testRealm;
        this.datasetCacheStatsURL = keycloakServerURL + "/realms/master/cache/sizes";
    }

    public DatacenterInfo(String keycloakServerURL, String infinispanRestEndpointURL) {
        this(keycloakServerURL, "realm-0", infinispanRestEndpointURL);
    }

    public String getKeycloakServerURL() {
        return keycloakServerURL;
    }

    public String getInfinispanServerURL() {
        return infinispanServerURL;
    }

    public String getTestRealm() {
        return testRealm;
    }

    public String getTestRealmURL() {
        return testRealmURL;
    }

    public String getLoginEndpoint() {
        return loginEndpoint;
    }

    public String getLogoutEndpoint() {
        return logoutEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public String getRedirectURI() {
        return redirectURI;
    }

    public String getDatasetStatusURL() {
        return datasetStatusURL;
    }

    public String getDatasetLastClientURL() {
        return datasetLastClientURL;
    }

    public String getDatasetLastUserURL() {
        return datasetLastUserURL;
    }

    public String getDatasetCacheStatsURL() {return datasetCacheStatsURL;}

}
