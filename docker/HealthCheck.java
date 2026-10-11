import java.net.HttpURLConnection;
import java.net.URI;

/**
 * HEALTHCHECK de la imagen: distroless no trae shell ni curl. Sale 0 si la
 * readiness responde 200; cualquier otra cosa (incluida una excepcion) sale
 * distinto de 0 y Docker marca el contenedor unhealthy.
 */
public class HealthCheck {

    public static void main(String[] args) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create("http://localhost:8080/actuator/health/readiness").toURL().openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        System.exit(conn.getResponseCode() == 200 ? 0 : 1);
    }
}
