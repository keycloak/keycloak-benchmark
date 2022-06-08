package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class CreateUsers extends CommonSimulation {

  setUp("Create, Read Users", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createUser()
    .listUsers()
  );
}
