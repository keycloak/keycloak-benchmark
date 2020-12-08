package keycloak

import java.util

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import keycloak.OIDCScenarioBuilder._
import java.util.concurrent.atomic.AtomicInteger

import io.gatling.core.pause.Normal
import io.gatling.core.session.Session
import io.gatling.core.structure.ChainBuilder
import io.gatling.core.validation.Validation
import org.jboss.perf.util.Util
import org.jboss.perf.util.Util.randomUUID
import org.keycloak.performance.TestConfig

import scala.util.Random


/**
  * @author <a href="mailto:mstrukel@redhat.com">Marko Strukelj</a>
  */
object OIDCScenarioBuilder {

  val BASE_URL = "${keycloakServer}/realms/${realm}"
  val LOGIN_ENDPOINT = BASE_URL + "/protocol/openid-connect/auth"
  val LOGOUT_ENDPOINT = BASE_URL + "/protocol/openid-connect/logout"
  val TOKEN_ENDPOINT = BASE_URL + "/protocol/openid-connect/token"

  // Specify defaults for http requests
  val UI_HEADERS = Map(
    "Accept" -> "text/html,application/xhtml+xml,application/xml",
    "Accept-Encoding" -> "gzip, deflate",
    "Accept-Language" -> "en-US,en;q=0.5",
    "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")

  val ACCEPT_JSON = Map("Accept" -> "application/json")
  val ACCEPT_ALL = Map("Accept" -> "*/*")

  def downCounterAboveZero(session: Session, attrName: String): Validation[Boolean] = {
    val missCounter = session.attributes.get(attrName) match {
      case Some(result) => result.asInstanceOf[AtomicInteger]
      case None => new AtomicInteger(0)
    }
    missCounter.getAndDecrement() > 0
  }
  
  val httpDefault = http
    .acceptHeader("application/json")
    .disableFollowRedirect
    .inferHtmlResources

  val loginAndLogoutScenario = new OIDCScenarioBuilder()
      .browserOpensLoginPage()
      .thinkPause()
      .browserPostsWrongCredentials()
      .browserPostsCorrectCredentials()

      // Act as client adapter - exchange code for keys
//      .adapterExchangesCodeForTokens()

      .thinkPause()
      .randomLogout()
  
  val registerAndLogoutScenario = new OIDCScenarioBuilder()
      .browserOpensLoginPage()
      .thinkPause()
      .browserOpensRegistrationPage()
      .thinkPause()
      .browserPostsRegistrationDetails()
      .thinkPause()
      .randomLogout()

  val clientCredentialsScenario = new OIDCScenarioBuilder()
    .clientCredentialsGrant()
    .thinkPause()
}


class OIDCScenarioBuilder {

  var chainBuilder = exec(s => {

      val serverUrl = TestConfig.serverUrisList.iterator().next()
      val realmIndex = String.valueOf(Random.nextInt(TestConfig.numOfRealms))
      val clientIndex = String.valueOf(Random.nextInt(TestConfig.numClientsPerRealm))
      val userIndex = String.valueOf(Random.nextInt(TestConfig.numUsersPerRealm))
      var realmName = "realm-".concat(realmIndex)

      if (TestConfig.realmName != null) {
          realmName = TestConfig.realmName
      }

      var userName = "user-".concat(userIndex)

      if (TestConfig.userName != null) {
        userName = TestConfig.userName
      }

      var userPassword = "user-".concat(userIndex).concat("-password")

      if (TestConfig.userPassword != null) {
        userPassword = TestConfig.userPassword
      }

      s.setAll("keycloakServer" -> serverUrl,
          "state" -> randomUUID(),
          "wrongPasswordCount" -> new AtomicInteger(TestConfig.badLoginAttempts),
          "realm" -> realmName,
          "firstName" -> "",
          "lastName" -> "",
          "email" -> "",
          "username" -> userName,
          "password" -> userPassword,
          "clientId" -> "client-".concat(clientIndex),
          "secret" -> "client-".concat(clientIndex).concat("-secret"),
          "appUrl" -> serverUrl.concat("/realms/").concat(realmName).concat("/account")
        )
    })
    .exitHereIfFailed

  def thinkPause() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder.pause(TestConfig.userThinkTime, Normal(TestConfig.userThinkTime * 0.2))
    this
  }

  def thinkPause(builder: ChainBuilder) : ChainBuilder = {
    builder.pause(TestConfig.userThinkTime, Normal(TestConfig.userThinkTime * 0.2))
  }

  def newThinkPause() : ChainBuilder = {
    pause(TestConfig.userThinkTime, Normal(TestConfig.userThinkTime * 0.2))
  }
  
  def browserOpensLoginPage() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser to Log In Endpoint")
        .get(LOGIN_ENDPOINT)
        .headers(UI_HEADERS)
        .queryParam("login", "true")
        .queryParam("response_type", "code")
        .queryParam("client_id", "account")
        .queryParam("state", "${state}")
        .queryParam("redirect_uri", "${appUrl}")
        .queryParam("scope", "openid profile")
        .check(status.is(200), 
          regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("login-form-uri"),
          regex("href=\"/auth(/realms/[^\"]*/login-actions/registration[^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("registration-link")))
        // if already logged in the check will fail with:
        // status.find.is(200), but actually found 302
        // The reason is that instead of returning the login page we are immediately redirected to the app that requested authentication
      .exitHereIfFailed
    this
  }

  def browserPostsWrongCredentials() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
      .asLongAs(s => downCounterAboveZero(s, "wrongPasswordCount")) {
        var c = exec(http("Browser posts wrong credentials")
          .post("${login-form-uri}")
          .headers(UI_HEADERS)
          .formParam("username", "${username}")
          .formParam("password", _ => Util.randomString(10))
          .formParam("login", "Log in")
          .check(status.is(200), regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("login-form-uri")))
          .exitHereIfFailed

        // make sure to call the right version of thinkPause - one that takes chainBuilder as argument
        // - because this is a nested chainBuilder - not the same as chainBuilder field
        thinkPause(c)
      }
    this
  }

  def browserPostsCorrectCredentials() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser posts correct credentials")
        .post("${login-form-uri}")
        .headers(UI_HEADERS)
        .formParam("username", "${username}")
        .formParam("password", "${password}")
        .formParam("login", "Log in")
        .check(status.is(302), header("Location").saveAs("login-redirect")))
      .exitHereIfFailed
    this
  }

  def browserOpensRegistrationPage() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser to Registration Endpoint")
        .get("${keycloakServer}${registration-link}")
        .headers(UI_HEADERS)
        .check(
          status.is(200), 
          regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("registration-form-uri"))
        )
      .exitHereIfFailed
    this
  }

  def browserPostsRegistrationDetails() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser posts registration details")
        .post("${registration-form-uri}")
        .headers(UI_HEADERS)
        .formParam("firstName", "${firstName}")
        .formParam("lastName", "${lastName}")
        .formParam("email", "${email}")
        .formParam("username", "${username}")
        .formParam("password", "${password}")
        .formParam("password-confirm", "${password}")
        .check(status.is(302), header("Location").saveAs("login-redirect")))
      .exitHereIfFailed
    this
  }

  def logoutChain() : ChainBuilder = {
    exec(http("Browser logout")
        .get(LOGOUT_ENDPOINT)
        .headers(UI_HEADERS)
        .queryParam("redirect_uri", "${appUrl}")
        .check(status.is(302), header("Location").is("${appUrl}")))
  }
  
  def logout() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder.exec(logoutChain)
    this
  }
  
  def randomLogout() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
    .randomSwitch(
      // logout randomly based on logoutPct param
      TestConfig.logoutPct -> exec(logoutChain)
    )
    this
  }

  def clientCredentialsGrant() : OIDCScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Client credentials grant type")
        .post(TOKEN_ENDPOINT)
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", "${clientId}")
        .formParam("client_secret", "${secret}")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }
  
}

