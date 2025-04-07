package org.keycloak.benchmark;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;

/**
 * @author <a href="mailto:mstrukel@redhat.com">Marko Strukelj</a>
 * @author <a href="mailto:tkyjovsk@redhat.com">Tomas Kyjovsky</a>
 */
public class Config {

    /**
     * The number of realms available. This number is going to be used to run test scenarios using realms from 0 to {@code realms} - 1.
     */
    public static final int numOfRealms = Integer.getInteger("realms", 1);

    /**
     * The number of users available in each realm. This number is going to be used to run test scenarios using users from 0 to {@code users-per-realm} - 1.
     */
    public static final int numUsersPerRealm = Integer.getInteger("users-per-realm", 1);

    /**
     * The number of clients available in each realm. This number is going to be used to run test scenarios using clients from 0 to {@code users-per-realm} - 1.
     */
    public static final int numClientsPerRealm = Integer.getInteger("clients-per-realm", 1);

    /**
     * If set, tests will run using a single realm with name {@code realm-name}.
     */
    public static final String realmName = System.getProperty("realm-name");

    /**
     * Sets the prefix for realm names. If not set, realm names use the {@code realm-} prefix. Eg.: realm-0, realm-1.
     */
    public static final String realmPrefix = System.getProperty("realm-prefix", "realm-");

    /**
     * If set, tests will run using a single user with username {@code username}.
     */
    public static final String userName = System.getProperty("username");

    /**
     * Sets the prefix for usernames. If not set, usernames use the {@code user-} prefix. Eg.: user-0, user-1.
     */
    public static final String userNamePrefix = System.getProperty("username-prefix", "user-");

    /**
     * Sets the index offset for users and user passwords. If not set, index starts at {@code 0}. Eg.: user-0, user-1.
     */
    public static final int userIndexOffset = Integer.getInteger("user-index-offset", 0);

    /**
     * If set, tests running using a single user are going to use the password defined in {@code user-password}.
     */
    public static final String userPassword = System.getProperty("user-password");

    /**
     * Sets the prefix for passwords. If not set, passwords use the {@code user-} prefix. Eg.: user-0-password, user-1-password.
     */
    public static final String userPasswordPrefix = System.getProperty("user-password-prefix", "user-");

    /**
     * Sets the suffix for passwords. If not set, passwords use the {@code -password} suffix. Eg.: user-0-password, user-1-password.
     */
    public static final String userPasswordSuffix = System.getProperty("user-password-suffix", "-password");

    /**
     * If set, tests are going to run using a single client with the given {@code client-id}
     */
    public static final String clientId = System.getProperty("client-id");

    /**
     * If set, tests are going to run using a single client with the given {@code client-secret}
     */
    public static final String clientSecret = System.getProperty("client-secret");

    /**
     * If set, tests are going to run using a single client with the given {@code client-redirect-uri}
     */
    public static final String clientRedirectUrl = System.getProperty("client-redirect-uri");

    /**
     * If set, tests requiring admin credentials will run using the provided {@code admin-username}.
     */
    public static final String adminUsername = System.getProperty("admin-username");

    /**
     * If set, tests requiring admin credentials will run using the provided {@code admin-password}.
     */
    public static final String adminPassword = System.getProperty("admin-password");

    /**
     * A comma-separated list of scopes to be set when making authentication requests. If not set, the default scopes are "openid profile".
     */
    public static final String scope = System.getProperty("scope");

    /**
     * Used to specify basic endpoint to get
     */
    public static final String basicUrl = System.getProperty("basic-url");

    public static final Double usersPerSec;

    public static final Integer concurrentUsers;

    public static final WorkloadModel workloadModel;

    static {
        double usersPerSecTmp = Double.parseDouble(System.getProperty("users-per-sec", "0"));
        concurrentUsers = Integer.valueOf(System.getProperty("concurrent-users", "0"));
        if (usersPerSecTmp > 0 && concurrentUsers == 0) {
            workloadModel = WorkloadModel.OPEN;
        } else if (concurrentUsers > 0 && usersPerSecTmp == 0) {
            workloadModel = WorkloadModel.CLOSED;
        } else {
            workloadModel = WorkloadModel.OPEN; // default, like "--users-per-sec=1" was specified
            usersPerSecTmp=1.0;
        }
        usersPerSec = usersPerSecTmp;
    }

    /**
     * The ramp up period, in seconds, so that new users are linearly created in a period of time.
     */
    public static final int rampUpPeriod = Integer.getInteger("ramp-up", 5);

    /**
     * The warm up period, in seconds, so that tests run at maximum number of users for a period of time.
     */
    public static final int warmUpPeriod = Integer.getInteger("warm-up", 0);

    /**
     * The total measurement period.
     */
    public static final int measurementPeriod = Integer.getInteger("measurement", 30);

    /**
     * For tests that rely on user behavior, this option defines pauses, in seconds, when running tests.
     */
    public static final int userThinkTime = Integer.getInteger("user-think-time", 0);

    /**
     * For tests using logout, the percentage of users that should logout.
     */
    public static final double logoutPercentage = Double.parseDouble(System.getProperty("logout-percentage", "100"));

    /**
     * For tests relying on user login, the number of bad login attempts.
     */
    public static final int badLoginCount = Integer.getInteger("bad-login-count", 0);


    /**
     * If tests should infer HTML resources and include steps to fetch them automatically.
     */
    public static final boolean inferHtmlResources = Boolean.getBoolean("infer-html-resources");

    public static final int refreshTokenPeriod = Integer.getInteger("refresh-token-period", 0);
    public static final boolean filterResults = Boolean.getBoolean("filter-results"); // filter out results outside of measurementPeriod

    /**
     * Used by the AuthorizationCode scenario to specify how many times the token should be refreshed before the user logs out.
     */
    public static final int refreshTokenCount = Integer.getInteger("refresh-token-count", 0);

    public static final boolean refreshCloseHttpConnection = Boolean.getBoolean(System.getProperty("refresh-close-http-connection", "true"));

    /**
     * Whether to share TCP connections among the multiple users in the test scenario, possibly helpful
     * when the local testing system is overtaxed.  But this will induce a lesser load than real world.
     */
    public static final boolean shareConnections = Boolean.getBoolean("share-connections");
    public static final boolean httpProxy = Boolean.getBoolean("http-proxy");
    public static final String httpProxyHost = System.getProperty("http-proxy-host", "127.0.0.1");
    public static final int httpProxyPort = Integer.getInteger("http-proxy-port", 8888);

    public static final boolean useAllLocalAddresses = Boolean.getBoolean("use-all-local-addresses");

    // Computed timestamps
    public static final long simulationStartTime = System.currentTimeMillis();
    public static final long warmUpStartTime = simulationStartTime + rampUpPeriod * 1000L;
    public static final long measurementStartTime = warmUpStartTime + warmUpPeriod * 1000L;
    public static final long measurementEndTime = measurementStartTime + measurementPeriod * 1000L;

    public static final String serverUris;
    public static final List<String> serverUrisList;
    // assertion properties
    public static final double maxErrorPercentage = Double.parseDouble(System.getProperty("sla-error-percentage", "0"));
    public static final int maxMeanResponseTime = Integer.getInteger("sla-mean-response-time", 300);
    public static SimpleDateFormat SIMPLE_TIME = new SimpleDateFormat("HH:mm:ss");

    // user-crawl-scenario properties
    /**
     * The amount of users to be requested for each page. This number is only used in the user crawl scenario.
     */
    public static final int userPageSize = Integer.getInteger("user-page-size", 20);

    /**
     * The number of pages to iterate over. This number is only used in the user crawl scenario.
     */
    public static final int userNumberOfPages = Integer.getInteger("user-number-of-pages", 10);

    // join-group-scenario properties
    /**
     * The group name used to find a group and determine which group the user should join.
     */
    public static final String joinGroup_groupName = System.getProperty("join-group-group-name");

    static {
        // if KEYCLOAK_SERVER_URIS env var is set, and system property serverUris is not set
        String serversProp = System.getProperty("server-url");
        if (serversProp == null) {
            String serversEnv = System.getenv("KC_SERVER_URL");
            serverUris = serversEnv != null ? serversEnv : "http://0.0.0.0:8080";
        } else {
            serverUris = serversProp;
        }

        // initialize serverUrisList and serverUrisIterator
        serverUrisList = Arrays.asList(serverUris.split(" "));
    }

    public static void preventLocalhostServerUris() {
        serverUrisList.stream().forEach(s -> {
            URI uri = URI.create(s);
            if (uri.getScheme().equals("http") && isLocalhostSecureContext(uri)) {
                throw new RuntimeException("""
                                           Gatling won't send secure cookies to localhost addresses. Due to this, it is incompatible of running tests against with Keycloak 26.
                                           See https://github.com/keycloak/keycloak-benchmark/issues/945 for more information.
                                           As a workaround, use a different IP address on your current host, or use http://0.0.0.0
                                           """);
            }
        });
    }

    public static boolean isLocalhostSecureContext(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }

        // The host matches a CIDR notation of ::1/128
        if (host.equals("[::1]") || host.equals("[0000:0000:0000:0000:0000:0000:0000:0001]")) {
            return true;
        }

        // The host matches a CIDR notation of 127.0.0.0/8
        if (host.matches("127.\\d{1,3}.\\d{1,3}.\\d{1,3}")) {
            return true;
        }

        if (host.equals("localhost") || host.equals("localhost.")) {
            return true;
        }

        if (host.endsWith(".localhost") || host.endsWith(".localhost.")) {
            return true;
        }

        return false;
    }

    public static String toStringPopulationConfig() {
        return String.format(
                "  realms: %s\n"
                        + "  users-per-realm: %s\n"
                        + "  clients-per-realm: %s\n"
                        + "  realm-name: %s\n"
                        + "  username: %s\n"
                        + "  user-password: %s",
                numOfRealms, numUsersPerRealm, numClientsPerRealm,
                realmName == null ? "Not defined" : realmName,
                userName == null ? "Not defined" : userName,
                userPassword == null ? "Not defined" : userPassword);
    }

    public static String toStringRuntimeParameters() {
        return  (workloadModel == WorkloadModel.OPEN
                ? String.format("  users-per-sec: %s (Open Workload Model)\n", usersPerSec)
                : String.format("  concurrent-users: %s (Closed Workload Model)\n", concurrentUsers))
                + String.format("  ramp-up: %s\n"
                              + "  warm-up: %s\n"
                              + "  measurement: %s\n"
                              + "  user-think-time: %s\n"
                              + "  refresh-token-period: %s",
                rampUpPeriod, warmUpPeriod, measurementPeriod, userThinkTime, refreshTokenPeriod);
    }

    public static String toStringTimestamps() {
        return String.format("  Start: %s\n"
                        + "  Warm-Up Start: %s\n"
                        + "  Measurement Start: %s\n"
                        + "  Measurement End: %s",
                SIMPLE_TIME.format(simulationStartTime),
                SIMPLE_TIME.format(warmUpStartTime),
                SIMPLE_TIME.format(measurementStartTime),
                SIMPLE_TIME.format(measurementEndTime));
    }

    public static String toStringSLA() {
        return String.format("  Max Error Percentage: %s\n"
                           + "  Max Mean Response Time: %s",
                maxErrorPercentage,
                maxMeanResponseTime);
    }

    public static void validateConfiguration() {
        if (logoutPercentage < 0 || logoutPercentage > 100) {
            throw new RuntimeException("The `logoutPct` needs to be between 0 and 100.");
        }
    }

}
