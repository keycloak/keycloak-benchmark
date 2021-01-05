
import io.gatling.app.Gatling
import io.gatling.core.config.GatlingPropertiesBuilder
import keycloak.scenario.authentication.{AuthorizationCode, LoginUserPassword}

object Engine extends App {

  val sim = classOf[AuthorizationCode]

  val props = new GatlingPropertiesBuilder
  props.resultsDirectory(IDEPathHelper.resultsDirectory.toString)
  props.binariesDirectory(IDEPathHelper.mavenBinariesDirectory.toString)

  props.simulationClass(sim.getName)

  Gatling.fromMap(props.build)
}