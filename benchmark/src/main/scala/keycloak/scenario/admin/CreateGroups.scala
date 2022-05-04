package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateGroups extends CommonSimulation {

  setUp("CRUD Groups", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    //.listGroups()
    //.createGroups()
    //.deleteGroups()
  )
}
