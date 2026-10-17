package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class ListUsers extends CommonSimulation {

  setUp("List Users", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .viewPagesOfUsers(Config.pagesSize, Config.pagesTotal))
}
