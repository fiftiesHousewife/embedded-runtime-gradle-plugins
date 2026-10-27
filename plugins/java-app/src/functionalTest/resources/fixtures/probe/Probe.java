import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Prints what the JVM it runs on sees as its security settings and trusted CAs. */
public class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("ttl=" + Security.getProperty("networkaddress.cache.ttl"));
        System.out.println("serialFilter=" + Security.getProperty("jdk.serialFilter"));
        System.out.println("override=" + Security.getProperty("security.overridePropertiesFile"));
        System.out.println("tls=" + Security.getProperty("jdk.tls.disabledAlgorithms"));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509Certificate[] cas = ((X509TrustManager) factory.getTrustManagers()[0]).getAcceptedIssuers();
        System.out.println("trusted=" + cas.length);
        for (X509Certificate ca : cas) {
            System.out.println("ca=" + ca.getSubjectX500Principal().getName());
        }
    }
}
