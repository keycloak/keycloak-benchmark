package org.keycloak.performance;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;

/**
 * @author <a href="mailto:mstrukel@redhat.com">Marko Strukelj</a>
 * @author <a href="mailto:tkyjovsk@redhat.com">Tomas Kyjovsky</a>
 */
public class TestConfig {

    public static final int numOfRealms = Integer.getInteger("realms", 1);
    public static final int numUsersPerRealm = Integer.getInteger("users-per-realm", 1);
    public static final int numClientsPerRealm = Integer.getInteger("clients-per-realm", 1);

    public static final String realmName = System.getProperty("realm-name");
    public static final String userName = System.getProperty("username");
    public static final String userPassword = System.getProperty("user-password");
    public static final double usersPerSec = Double.valueOf(System.getProperty("users-per-sec", "1"));
    public static final int rampUpPeriod = Integer.getInteger("ramp-up", 0);
    public static final int warmUpPeriod = Integer.getInteger("warm-up", 0);
    public static final int measurementPeriod = Integer.getInteger("measurement", 30);
    public static final boolean filterResults = Boolean.getBoolean("filter-results"); // filter out results outside of measurementPeriod
    public static final int userThinkTime = Integer.getInteger("user-think-time", 0);
    public static final double logoutPct = Double.valueOf(System.getProperty("logout-pct", "100"));
    public static final int refreshTokenPeriod = Integer.getInteger("refreshTokenPeriod", 0);

    // Computed timestamps
    public static final long simulationStartTime = System.currentTimeMillis();
    public static final long warmUpStartTime = simulationStartTime + rampUpPeriod * 1000;
    public static final long measurementStartTime = warmUpStartTime + warmUpPeriod * 1000;
    public static final long measurementEndTime = measurementStartTime + measurementPeriod * 1000;

    public static final int badLoginAttempts = Integer.getInteger("bad-login-attempts", 0);

    public static final String serverUris;
    public static final List<String> serverUrisList;
    // assertion properties
    public static final int maxFailedRequests = Integer.getInteger("max-failed-requests", 0);
    public static final int maxMeanReponseTime = Integer.getInteger("max-mean-response-time", 300);
    public static SimpleDateFormat SIMPLE_TIME = new SimpleDateFormat("HH:mm:ss");

    static {
        // if KEYCLOAK_SERVER_URIS env var is set, and system property serverUris is not set
        String serversProp = System.getProperty("keycloak.server.uris");
        if (serversProp == null) {
            String serversEnv = System.getenv("KEYCLOAK_SERVERS");
            serverUris = serversEnv != null ? serversEnv : "http://localhost:8080/auth";
        } else {
            serverUris = serversProp;
        }

        // initialize serverUrisList and serverUrisIterator
        serverUrisList = Arrays.asList(serverUris.split(" "));
    }

    public static String toStringCommonTestParameters() {
        return String.format(
                "  usersPerSec: %s\n"
                        + "  rampUpPeriod: %s\n"
                        + "  warmUpPeriod: %s\n"
                        + "  measurementPeriod: %s\n"
                        + "  filterResults: %s\n"
                        + "  userThinkTime: %s\n"
                        + "  logoutPct: %s",
                usersPerSec, rampUpPeriod, warmUpPeriod, measurementPeriod, filterResults, userThinkTime, logoutPct);
    }

    public static String toStringTimestamps() {
        return String.format("  simulationStartTime: %s\n"
                        + "  warmUpStartTime: %s\n"
                        + "  measurementStartTime: %s\n"
                        + "  measurementEndTime: %s",
                SIMPLE_TIME.format(simulationStartTime),
                SIMPLE_TIME.format(warmUpStartTime),
                SIMPLE_TIME.format(measurementStartTime),
                SIMPLE_TIME.format(measurementEndTime));
    }

    public static String toStringAssertionProperties() {
        return String.format("  maxFailedRequests: %s\n"
                        + "  maxMeanReponseTime: %s",
                maxFailedRequests,
                maxMeanReponseTime);
    }

    public static void validateConfiguration() {
        if (logoutPct < 0 || logoutPct > 100) {
            throw new RuntimeException("The `logoutPct` needs to be between 0 and 100.");
        }
    }

}
