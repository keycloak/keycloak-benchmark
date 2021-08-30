package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class UserCrawl extends CommonSimulation {

  setUp("User Crawl", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .viewPagesOfUsers(Config.userPageSize,Config.userNumberOfPages))
}
