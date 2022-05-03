package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateRealms extends CommonSimulation {

  setUp("CRUD realms", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createRealm()
  )
}
