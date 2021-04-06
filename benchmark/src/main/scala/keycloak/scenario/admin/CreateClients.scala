package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateClients extends CommonSimulation {

  setUp("CRUD clients", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createClient()
    .listClients())

}
