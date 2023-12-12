package org.keycloak.benchmark.crossdc;

import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.benchmark.crossdc.client.DatacenterInfo;
import org.keycloak.benchmark.crossdc.client.KeycloakClient;
import org.keycloak.benchmark.crossdc.util.HttpClientUtils;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.http.HttpClient;
import java.util.Collections;
import java.util.List;

import static org.keycloak.benchmark.crossdc.util.HttpClientUtils.MOCK_COOKIE_MANAGER;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.DISTRIBUTED_CACHES;

public abstract class AbstractCrossDCTest {
    static HttpClient HTTP_CLIENT = HttpClientUtils.newHttpClient();
    protected static final DatacenterInfo DC_1, DC_2;
    protected static final KeycloakClient LOAD_BALANCER_KEYCLOAK;
    public static String ISPN_USERNAME = System.getProperty("infinispan.username", "developer");;
    public static final String REALM_NAME = "cross-dc-test-realm";
    public static final String CLIENTID = "cross-dc-test-client";
    public static final String CLIENT_SECRET = "cross-dc-test-client-secret";
    public static final String USERNAME = "cross-dc-test-user";
    public static final String MAIN_PASSWORD = System.getProperty("main.password");



    static {
        DC_1 = new DatacenterInfo(HTTP_CLIENT, System.getProperty("keycloak.dc1.url"), System.getProperty("infinispan.dc1.url"));
        DC_2 = new DatacenterInfo(HTTP_CLIENT, System.getProperty("keycloak.dc2.url"), System.getProperty("infinispan.dc2.url"));
        LOAD_BALANCER_KEYCLOAK = new KeycloakClient(HTTP_CLIENT, System.getProperty("load-balancer.url"));
    }

    @BeforeEach
    public void setUpTestEnvironment() {
        // We need to reconnect admin client before each test as we remove session after each test
        DC_1.kc().reconnectAdminClient();
        DC_2.kc().reconnectAdminClient();

        Keycloak adminClient = DC_1.kc().adminClient();
        DC_2.kc().adminClient().realm("master").toRepresentation();

        try {
            if (adminClient.realms().realm(REALM_NAME).toRepresentation() != null) {
                adminClient.realms().realm(REALM_NAME).remove();
            }
        } catch (NotFoundException e) {
            // Ignore
        }

        // Create realm
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(REALM_NAME);
        realm.setEnabled(Boolean.TRUE);
        adminClient.realms().create(realm);

        RealmResource realmResource = adminClient.realm(REALM_NAME);

        // Create client
        ClientRepresentation client = new ClientRepresentation();
        client.setEnabled(Boolean.TRUE);
        client.setClientId(CLIENTID);
        client.setSecret(CLIENT_SECRET);
        client.setRedirectUris(List.of("*"));
        client.setDirectAccessGrantsEnabled(true);
        client.setProtocol("openid-connect");
        realmResource.clients().create(client).close();

        // Create user
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(Boolean.TRUE);
        user.setUsername(USERNAME);

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(MAIN_PASSWORD);
        user.setCredentials(Collections.singletonList(credential));

        realmResource.users().create(user).close();
    }

    @AfterEach
    public void tearDownTestEnvironment() {
        Keycloak adminClient = DC_1.kc().adminClient();

        if (adminClient.realms().realm(REALM_NAME).toRepresentation() != null) {
            adminClient.realms().realm(REALM_NAME).remove();
        }

        // Logout all users in master realm
        DC_1.kc().adminClient().realm("master").logoutAll();

        DISTRIBUTED_CACHES.forEach(cache -> {
            DC_1.ispn().cache(cache).clear();
            DC_2.ispn().cache(cache).clear();
        });

        MOCK_COOKIE_MANAGER.getCookieStore().removeAll();
    }
}
