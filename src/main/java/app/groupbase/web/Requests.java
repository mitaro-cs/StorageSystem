package app.groupbase.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Мелочи про текущий HTTP-запрос. */
public final class Requests {

  private Requests() {}

  /**
   * Запрос пришёл с этого же компьютера напрямую, а не через прокси или туннель. Адрес клиента уже
   * заменён на настоящий по X-Forwarded-For (только от доверенных локальных прокси), поэтому запрос
   * участника через туннель сюда не попадает.
   */
  public static boolean fromThisComputer(HttpServletRequest req) {
    String ip = req.getRemoteAddr();
    if (ip == null || ip.isEmpty() || !(Character.isDigit(ip.charAt(0)) || ip.indexOf(':') >= 0)) {
      return false;
    }
    try {
      return InetAddress.getByName(ip).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  public static HttpServletRequest current() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
        .getRequest();
  }
}
