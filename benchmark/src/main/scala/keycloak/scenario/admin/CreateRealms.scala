package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateRealms extends CommonSimulation {

  setUp("Create realms", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createRealm()
  )
}
