import java.net.HttpURLConnection;
import java.net.URL;

public class TestHttp {
    public static void main(String[] args) {
        try {
            System.out.println("Testing localhost:9090...");
            URL url = new URL("http://127.0.0.1:9090/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            System.out.println("Response code: " + conn.getResponseCode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
