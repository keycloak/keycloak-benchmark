package keycloak

import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import scala.util.Random

/**
 * @author <a href="mailto:mstrukel@redhat.com">Marko Strukelj</a>
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
object Utils {

  def urlencode(url: String) = {
    URLEncoder.encode(url, "utf-8")
  }

  def urlEncodedRoot(url: String) = {
    URLEncoder.encode(url.split("/auth")(0), "utf-8")
  }

  val random = new Random(1234); // keep fixed seed

  def randomString(length: Int, rand: Random = ThreadLocalRandom.current()): String = {
    val sb = new StringBuilder;
    for (i <- 0 until length) {
      sb.append((rand.nextInt(26) + 'a').toChar)
    }
    sb.toString()
  }

  def randomUUID(rand: Random = ThreadLocalRandom.current()): String =
    new UUID(rand.nextLong(), rand.nextLong()).toString
}
