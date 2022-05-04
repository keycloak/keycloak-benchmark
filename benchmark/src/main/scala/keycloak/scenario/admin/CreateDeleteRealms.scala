package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateDeleteRealms extends CommonSimulation {

  setUp("Create then Delete realms", new KeycloakScenarioBuilder()
    .adminCliToken()
    .createRealm()
    .deleteRealm()
  )
}
