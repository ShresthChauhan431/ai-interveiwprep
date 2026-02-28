import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class TestAuth {
    public static void main(String[] args) {
        String apiKey = "Y2hhdWhhbnNocmVzdGg3NzhAZ21haWwuY29t:g0Hlp1HIceWtTgzC6UPjU";
        String auth = apiKey + ":";
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.US_ASCII));
        String authHeader = "Basic " + new String(encodedAuth);
        System.out.println(authHeader);
    }
}
