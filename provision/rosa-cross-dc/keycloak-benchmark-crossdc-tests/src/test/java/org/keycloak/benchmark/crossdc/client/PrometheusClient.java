package org.keycloak.benchmark.crossdc.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.keycloak.benchmark.crossdc.util.HttpClientUtils;

import com.jayway.jsonpath.JsonPath;

import net.minidev.json.JSONArray;

public class PrometheusClient {

   final String url;
   final String token;

   public PrometheusClient(DatacenterInfo dc) {
      this.token = dc.oc().getConfiguration().getAutoOAuthToken();
      this.url = String.format("https://%s/api/v1",
            dc.oc().routes()
                  .inNamespace("openshift-monitoring")
                  .withName("thanos-querier")
                  .get()
                  .getSpec()
                  .getHost()
      );
   }

   public boolean isAlertFiring(String alertName) {
      HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url + "/alerts"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

      try {
         var body = HttpClientUtils.newHttpClient()
               .send(request, HttpResponse.BodyHandlers.ofString())
               .body();

         var jsonPath = String.format("$.data.alerts[?(@.labels.alertname == '%s' && @.state == 'firing')]", alertName);
         JSONArray alerts = JsonPath.read(body, jsonPath);
         return !alerts.isEmpty();
      } catch (IOException e) {
         throw new RuntimeException(e);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new RuntimeException(e);
      }
   }
}
