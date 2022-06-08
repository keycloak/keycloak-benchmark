package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}

class CreateDeleteUsers extends CommonSimulation {

  setUp("Create, Read and Delete Users", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createUser()
    .listUsers()
    .deleteUser()
  );
}
