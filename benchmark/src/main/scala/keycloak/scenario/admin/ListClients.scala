package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class ListClients extends CommonSimulation {

  setUp("List Clients", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .viewPagesOfClients(Config.pagesSize, Config.pagesTotal))
}
