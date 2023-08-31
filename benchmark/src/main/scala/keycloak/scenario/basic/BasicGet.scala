package keycloak.scenario.basic

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class Get extends CommonSimulation {
  setUp("Get - " + Config.basicUrl, new KeycloakScenarioBuilder()
    .basicGet(Config.basicUrl)
    .userThinkPause())
}
