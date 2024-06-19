package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.CLIENT_SESSIONS;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.SESSIONS;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.util.Map;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Disabled;
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

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC1
        String sessionId = code.split("[.]")[1];
        assertTrue(DC_1.ispn().cache(SESSIONS).contains(sessionId),
                () -> "External session cache in DC1 should contain session id [" + sessionId + "] but contains " + DC_1.ispn().cache(SESSIONS).keys());

        //Verify session cache size in external ISPN DC1
        //Contains 2 sessions because admin client creates one and the test the other
        assertEquals(1, DC_1.ispn().cache(SESSIONS).size(),
                () -> "External session cache in DC1 should contain 2 sessions " + sessionId + " but contains " + DC_1.ispn().cache(SESSIONS).keys());

        //Verify session cache size in embedded ISPN DC1
        //Contains 2 sessions because admin client creates one and the test the other
        assertEquals(1, DC_1.kc().embeddedIspn().cache(SESSIONS).size());

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC2
        assertTrue(DC_2.ispn().cache(SESSIONS).contains(sessionId),
                () -> "External session cache in DC2 should contains session id [" + sessionId + "] but contains " + DC_2.ispn().cache(SESSIONS).keys());
        //Verify session cache size in external ISPN DC2
        assertEquals(1, DC_2.ispn().cache(SESSIONS).size());
        //Verify session cache size in embedded ISPN DC2
        assertEquals(1, DC_2.kc().embeddedIspn().cache(SESSIONS).size());

        //Logout from DC1
        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        //Verify session cache size in external ISPN DC1 post logout
        assertEquals(0, DC_1.ispn().cache(SESSIONS).size());
        //Verify session cache size in embedded ISPN DC1 post logout
        assertEquals(0, DC_1.kc().embeddedIspn().cache(SESSIONS).size());

        //Verify session cache size in external ISPN  DC2 post logout
        assertEquals(0, DC_2.ispn().cache(SESSIONS).size());
        //Verify session cache size in embedded ISPN DC2
        assertEquals(0, DC_2.kc().embeddedIspn().cache(SESSIONS).size());
    }

    @Test
    public void testRefreshTokenRevocation() throws Exception {
        assertCacheSize(SESSIONS, 0);
        assertCacheSize(CLIENT_SESSIONS, 0);
        for (int i = 0; i < 20; i++) {
            // Create a new user session
            Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);
            assertCacheSize(SESSIONS, 1);
            assertCacheSize(CLIENT_SESSIONS, 1);

            // Do the token revocation
            HttpResponse<String> stringHttpResponse = DC_1.kc().revokeRefreshToken(REALM_NAME, CLIENTID, (String) tokensMap.get("refresh_token"));
            assertEquals(200, stringHttpResponse.statusCode());
            assertEquals("", stringHttpResponse.body());

            // The revocation should clean all sessions
            assertCacheSize(SESSIONS, 0);
            assertCacheSize(CLIENT_SESSIONS, 0);
        }
    }

    @Test
    public void testRemoteStoreDiscrepancyMissingSessionInPrimaryRemoteISPN() throws URISyntaxException, IOException, InterruptedException {
        // Create a new user session
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);

        // Make sure all ISPNs can see the entry in the cache
        assertTrue(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));

        // Remove the session from the remote store in DC1 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_1.ispn().cache(SESSIONS), DC_2.ispn().siteName())) {
            assertFalse(DC_1.ispn().cache(SESSIONS).isBackupOnline(DC_2.ispn().siteName()));
            DC_1.ispn().cache(SESSIONS).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_1.ispn().cache(SESSIONS).isBackupOnline(DC_2.ispn().siteName()));

        assertTrue(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        assertFalse(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
    }

    @Test
    public void testRemoteStoreDiscrepancyMissingSessionInBackupRemoteISPN() throws URISyntaxException, IOException, InterruptedException {
        // Create a new user session
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);

        // Make sure all ISPNs can see the entry in the cache
        assertTrue(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));

        // Remove the session from the remote store in DC2 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_2.ispn().cache(SESSIONS), DC_1.ispn().siteName())) {
            assertFalse(DC_2.ispn().cache(SESSIONS).isBackupOnline(DC_1.ispn().siteName()));
            DC_2.ispn().cache(SESSIONS).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_2.ispn().cache(SESSIONS).isBackupOnline(DC_1.ispn().siteName()));

        assertTrue(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        assertFalse(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
    }

    @Test
    public void testRemoteStoreDiscrepancyMissingSessionInAllRemoteISPN() throws URISyntaxException, IOException, InterruptedException {
        // Create a new user session
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.passwordGrant(REALM_NAME, CLIENTID, USERNAME, MAIN_PASSWORD);
        LOG.info("processing session " + tokensMap.get("session_state"));

        // Make sure all ISPNs can see the entry in the cache
        assertTrue(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));

        // Remove the session from the remote store in DC1 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_1.ispn().cache(SESSIONS), DC_2.ispn().siteName())) {
            assertFalse(DC_1.ispn().cache(SESSIONS).isBackupOnline(DC_2.ispn().siteName()));
            DC_1.ispn().cache(SESSIONS).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_1.ispn().cache(SESSIONS).isBackupOnline(DC_2.ispn().siteName()));

        // Remove the session from the remote store in DC2 only
        try (var ignored = InfinispanUtils.withBackupDisabled(DC_2.ispn().cache(SESSIONS), DC_1.ispn().siteName())) {
            assertFalse(DC_2.ispn().cache(SESSIONS).isBackupOnline(DC_1.ispn().siteName()));
            DC_2.ispn().cache(SESSIONS).remove((String) tokensMap.get("session_state"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertTrue(DC_2.ispn().cache(SESSIONS).isBackupOnline(DC_1.ispn().siteName()));

        assertTrue(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertTrue(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));

        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        assertFalse(DC_1.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_1.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.kc().embeddedIspn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
        assertFalse(DC_2.ispn().cache(SESSIONS).contains((String) tokensMap.get("session_state")));
    }
}
