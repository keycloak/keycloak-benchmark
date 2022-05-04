package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateClients extends CommonSimulation {

  setUp("Create and List clients", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createClient()
    .listClients())

}
