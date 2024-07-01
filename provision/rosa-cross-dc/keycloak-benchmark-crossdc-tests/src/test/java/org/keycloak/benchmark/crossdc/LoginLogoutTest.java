package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLIENT_SESSION_CACHE_NAME;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.USER_SESSION_CACHE_NAME;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.util.Map;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.keycloak.benchmark.crossdc.util.InfinispanUtils;


public class LoginLogoutTest extends AbstractCrossDCTest {

    protected static final Logger LOG = Logger.getLogger(LoginLogoutTest.class);

    @Test
    public void loginLogoutTest() throws URISyntaxException, IOException, InterruptedException {
        //Login and exchange code in DC1
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin(REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        //Making sure the code cannot be reused in any of the DCs
        DC_2.kc().exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 400, code, LOAD_BALANCER_KEYCLOAK.getRedirectUri(REALM_NAME));
        DC_1.kc().exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 400, code, LOAD_BALANCER_KEYCLOAK.getRedirectUri(REALM_NAME));

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache
        String sessionId = code.split("[.]")[1];
        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, sessionId);
        assertExternalCacheContainsAllDCs(USER_SESSION_CACHE_NAME, sessionId);
        assertCacheSize(USER_SESSION_CACHE_NAME, 1);

        //Logout from DC1
        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        //Verify session cache size in post logout
        assertCacheSize(USER_SESSION_CACHE_NAME, 0);
    }

    @Test
    public void testRefreshTokenRevocation() throws Exception {
        assertCacheSize(USER_SESSION_CACHE_NAME, 0);
        assertCacheSize(CLIENT_SESSION_CACHE_NAME, 0);
        for (int i = 0; i < 20; i++) {
            // Create a new user session
            Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);
            assertCacheSize(USER_SESSION_CACHE_NAME, 1);
            assertCacheSize(CLIENT_SESSION_CACHE_NAME, 1);

            // Do the token revocation
            HttpResponse<String> stringHttpResponse = DC_1.kc().revokeRefreshToken(REALM_NAME, CLIENTID, (String) tokensMap.get("refresh_token"));
            assertEquals(200, stringHttpResponse.statusCode());
            assertEquals("", stringHttpResponse.body());

            // The revocation should clean all sessions
            assertCacheSize(USER_SESSION_CACHE_NAME, 0);
            assertCacheSize(CLIENT_SESSION_CACHE_NAME, 0);
        }
    }

    @Test
    public void testRemoteStoreDiscrepancyMissingSessionInPrimaryRemoteISPN() throws URISyntaxException, IOException, InterruptedException {
        // after manual removing the key, it is impossible to get the UUID of the session to remove from the backup site.
        Assumptions.assumeFalse(SKIP_EMBEDDED_CACHES, "Test is invalid with external infinispan.");
        // Create a new user session
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);

        // Make sure all ISPNs can see the entry in the cache
        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));

        // Remove the session from the remote store in DC1 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_1.ispn().cache(USER_SESSION_CACHE_NAME), DC_2.ispn().siteName())) {
            assertFalse(DC_1.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_2.ispn().siteName()));
            DC_1.ispn().cache(USER_SESSION_CACHE_NAME).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_1.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_2.ispn().siteName()));

        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheNotContains(DC_1, USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheContains(DC_2, USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        assertExternalCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertEmbeddedCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
    }

    @Test
    public void testRemoteStoreDiscrepancyMissingSessionInBackupRemoteISPN() throws URISyntaxException, IOException, InterruptedException {
        // Create a new user session
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);

        // Make sure all ISPNs can see the entry in the cache
        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));

        // Remove the session from the remote store in DC2 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_2.ispn().cache(USER_SESSION_CACHE_NAME), DC_1.ispn().siteName())) {
            assertFalse(DC_2.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_1.ispn().siteName()));
            DC_2.ispn().cache(USER_SESSION_CACHE_NAME).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_2.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_1.ispn().siteName()));

        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheContains(DC_1, USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheNotContains(DC_2, USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        assertExternalCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertEmbeddedCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
    }

    @Test
    public void testRemoteStoreDiscrepancyMissingSessionInAllRemoteISPN() throws URISyntaxException, IOException, InterruptedException {
        // Create a new user session
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);
        LOG.info("processing session " + tokensMap.get("session_state"));

        // Make sure all ISPNs can see the entry in the cache
        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));

        // Remove the session from the remote store in DC1 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_1.ispn().cache(USER_SESSION_CACHE_NAME), DC_2.ispn().siteName())) {
            assertFalse(DC_1.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_2.ispn().siteName()));
            DC_1.ispn().cache(USER_SESSION_CACHE_NAME).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_1.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_2.ispn().siteName()));

        // Remove the session from the remote store in DC2 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_2.ispn().cache(USER_SESSION_CACHE_NAME), DC_1.ispn().siteName())) {
            assertFalse(DC_2.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_1.ispn().siteName()));
            DC_2.ispn().cache(USER_SESSION_CACHE_NAME).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_2.ispn().cache(USER_SESSION_CACHE_NAME).isBackupOnline(DC_1.ispn().siteName()));

        assertEmbeddedCacheContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertExternalCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        assertExternalCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
        assertEmbeddedCacheNotContainsAllDCs(USER_SESSION_CACHE_NAME, (String) tokensMap.get("session_state"));
    }
}
