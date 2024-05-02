package org.keycloak.benchmark.crossdc.client;

import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;

import org.jboss.logging.Logger;

import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TagDescription;
import software.amazon.awssdk.services.globalaccelerator.GlobalAcceleratorClient;
import software.amazon.awssdk.services.globalaccelerator.model.Accelerator;
import software.amazon.awssdk.services.globalaccelerator.model.EndpointConfiguration;
import software.amazon.awssdk.services.globalaccelerator.model.EndpointDescription;
import software.amazon.awssdk.services.globalaccelerator.model.EndpointGroup;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.HealthCheck;
import software.amazon.awssdk.services.route53.model.UpdateHealthCheckRequest;
import software.amazon.awssdk.utils.builder.SdkBuilder;

public class AWSClient {

   private static final Logger LOG = Logger.getLogger(AWSClient.class);

   public static void updateRoute53HealthCheckPath(String domainName, String path) {
      try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
           Route53Client route53 = Route53Client.builder().httpClient(httpClient).build();
           CloudWatchClient cloudWatch = CloudWatchClient.builder().region(Region.US_EAST_1).httpClient(httpClient).build()) {

         String healthCheckId = null;
         for (HealthCheck hc : route53.listHealthChecks(SdkBuilder::build).healthChecks()) {
            if (domainName.equals(hc.healthCheckConfig().fullyQualifiedDomainName())) {
               healthCheckId = hc.id();
               break;
            }
         }
         LOG.infof("Updating Route53 HealthCheck '%s' for Domain='%s' to path='%s'", healthCheckId, domainName, path);
         route53.updateHealthCheck(
               UpdateHealthCheckRequest.builder()
                     .healthCheckId(healthCheckId)
                     .resourcePath(path)
                     .build()
         );

         // Wait for the HealthCheck Alarm to be in the OK state
         LOG.infof("Waiting for CloudWatch Alarm '%s' to be in state OK", healthCheckId);
         cloudWatch.waiter().waitUntilAlarmExists(
               DescribeAlarmsRequest.builder()
                     .alarmNames(healthCheckId)
                     .stateValue(StateValue.OK)
                     .build(),
               WaiterOverrideConfiguration.builder()
                     .waitTimeout(Duration.ofMinutes(10))
                     .build()
         );
      }
   }

   public static void acceleratorFallback(String acceleratorDns) {
      acceleratorClient((httpClient, gaClient) -> {
         // Retrieve Accelerator instance based upon DNS
         Accelerator accelerator = gaClient.listAccelerators().accelerators()
               .stream()
               .filter(a -> acceleratorDns.contains(a.dnsName()))
               .findFirst()
               .orElseThrow();

         var endpointGroup = getEndpointGroup(gaClient, accelerator);
         var region = endpointGroup.endpointGroupRegion();

         List<String> endpoints;
         try (ElasticLoadBalancingV2Client elbClient =
                    ElasticLoadBalancingV2Client.builder()
                          .region(Region.of(region))
                          .httpClient(httpClient)
                          .build()
         ) {
            // Get all LBs associated with the Accelerator
            var elbs = elbClient.describeLoadBalancers()
                  .loadBalancers()
                  .stream()
                  .filter(lb -> lb.type() == LoadBalancerTypeEnum.NETWORK)
                  .map(LoadBalancer::loadBalancerArn)
                  .toList();

            endpoints = elbClient.describeTags(b -> b.resourceArns(elbs))
                  .tagDescriptions()
                  .stream()
                  .filter(td -> td.tags()
                        .contains(
                              Tag.builder()
                                    .key("accelerator")
                                    .value(accelerator.name())
                                    .build()
                        )
                  )
                  .map(TagDescription::resourceArn)
                  .toList();
         }

         var endpointConfigs = endpoints.stream()
               .map(elb -> EndpointConfiguration.builder()
                     .clientIPPreservationEnabled(false)
                     .endpointId(elb)
                     .weight(50)
                     .build()
               ).toList();

         // Add all LBs to the Accelerator EndpointGroup
         return gaClient.updateEndpointGroup(
               g -> g.endpointGroupArn(endpointGroup.endpointGroupArn())
                     .endpointConfigurations(endpointConfigs)
         );
      });
   }

   private static EndpointGroup getEndpointGroup(GlobalAcceleratorClient gaClient, Accelerator accelerator) {
      var listenerArn = gaClient.listListeners(b -> b.acceleratorArn(accelerator.acceleratorArn()))
            .listeners()
            .stream()
            .findFirst()
            .orElseThrow()
            .listenerArn();

      return gaClient.listEndpointGroups(b -> b.listenerArn(listenerArn))
            .endpointGroups()
            .stream()
            .findFirst()
            .orElseThrow();
   }

   public static List<String> getAcceleratorEndpoints(String acceleratorDns) {
      return acceleratorClient((httpClient, gaClient) -> {
               // Retrieve Accelerator instance based upon DNS
               Accelerator accelerator = gaClient.listAccelerators().accelerators()
                     .stream()
                     .filter(a -> acceleratorDns.contains(a.dnsName()))
                     .findFirst()
                     .orElseThrow();

               return getEndpointGroup(gaClient, accelerator)
                     .endpointDescriptions()
                     .stream()
                     .map(EndpointDescription::endpointId)
                     .toList();
            }
      );
   }

   private static <T> T acceleratorClient(BiFunction<SdkHttpClient, GlobalAcceleratorClient, T> fn) {
      try (
            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            GlobalAcceleratorClient gaClient = GlobalAcceleratorClient.builder()
                  .region(Region.US_WEST_2)
                  .httpClient(httpClient)
                  .build()
      ) {
         return fn.apply(httpClient, gaClient);
      }
   }
}
