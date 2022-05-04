package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateRoles extends CommonSimulation {

  setUp("CRUD Roles", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    //.createRoles()
  )
}
