package org.keycloak.benchmark.crossdc.util;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public class KeycloakUtils {
    private static final Logger LOG = Logger.getLogger(KeycloakUtils.class);

    public static ResteasyClientBuilder newResteasyClientBuilder() {
        // Disable PKIX path validation errors when running tests using SSL
        HostnameVerifier hostnameVerifier = new HostnameVerifier() {
            @Override
            public boolean verify(String hostName, SSLSession session) {
                return true;
            }
        };
        return ((ResteasyClientBuilder) ResteasyClientBuilder.newBuilder()).disableTrustManager().hostnameVerifier(hostnameVerifier);
    }

    public static String getFormDataAsString(Map<String, String> formData) {
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

    public static String getCreatedId(Response response) {
        URI location = response.getLocation();
        if (!response.getStatusInfo().equals(Response.Status.CREATED)) {
            Response.StatusType statusInfo = response.getStatusInfo();
            response.bufferEntity();
            String body = response.readEntity(String.class);
            throw new WebApplicationException("Create method returned status "
                    + statusInfo.getReasonPhrase() + " (Code: " + statusInfo.getStatusCode() + "); expected status: Created (201). Response body: " + body, response);
        }
        if (location == null) {
            return null;
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public static boolean pointsToSameIp(String firstUrl, String secondUrl) throws UnknownHostException {
        InetAddress[] firstUrlAddresses = InetAddress.getAllByName(firstUrl);
        Object[] firstUrlIPs = Arrays.stream(firstUrlAddresses).map(InetAddress::getHostAddress).sorted().toArray();

        InetAddress[] secondUrlAddresses = InetAddress.getAllByName(secondUrl);
        Object[] secondUrlIPs = Arrays.stream(secondUrlAddresses).map(InetAddress::getHostAddress).sorted().toArray();

        return Arrays.equals(firstUrlIPs, secondUrlIPs);
    }

    public static String URIToHostString(String uri) {
        return URI.create(uri).getHost().toString();
    }
}
