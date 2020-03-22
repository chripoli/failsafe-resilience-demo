package loc.chripoli.resilience_demo;

import net.jodah.failsafe.CircuitBreaker;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.glassfish.jersey.client.JerseyClient;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.glassfish.jersey.client.JerseyWebTarget;

import javax.ws.rs.core.Response;

/**
 * Class which is used to generate a simple Jersey 2.x client and call an endpoint.
 *
 * @author chripoli
 */
public class JerseyTestClient {

    /**
     * Executes a HTTP request with resilience options enabled.
     *
     * @param url
     *          URL to call
     * @param retryPolicy
     *          RetryPolicy to use
     * @param circuitBreaker
     *          CircuitBreaker configuration to use
     * @return
     *          Response
     */
    public Response executeCall(final String url, final RetryPolicy<Response> retryPolicy, final CircuitBreaker<Response> circuitBreaker) {

        return Failsafe.with(retryPolicy, circuitBreaker).get(() -> {
            final JerseyClient client = JerseyClientBuilder.createClient();
            final JerseyWebTarget target = client.target(url);
            return target.request().get();
        });

    }

    /**
     * Executes a HTTP call without failsafe options.
     * @param url
     *      URL to call
     * @return
     *      HTTP response
     */
    public Response executeCall(final String url) {

            final JerseyClient client = JerseyClientBuilder.createClient();
            final JerseyWebTarget target = client.target(url);
            return target.request().get();

    }
}
