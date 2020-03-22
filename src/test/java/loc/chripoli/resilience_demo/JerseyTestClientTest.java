package loc.chripoli.resilience_demo;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.ConsoleNotifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import net.jodah.failsafe.CircuitBreaker;
import net.jodah.failsafe.RetryPolicy;
import org.junit.*;

import static java.lang.System.out;
import static java.lang.Thread.*;
import static org.junit.Assert.*;

import javax.ws.rs.core.Response;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test class for the JerseyTestClient.
 * Handles resilience tests.
 *
 * @author chripoli
 */
public class JerseyTestClientTest {

    /**
     * Number of executor threads.
     */
    public final int numberOfThreads = 30;

    /**
     * WireMock rule
     */
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(new WireMockConfiguration().port(8089).notifier(new ConsoleNotifier(true)));

    /**
     * Setup before the test.
     * Initialization of WireMock stubs.
     */
    @Before
    public void setup() {
        // WireMock stub for normal operation.
        // If ScenarioState is Scenario.STARTED, it returns a http response with status 200
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/test"))
                .inScenario("Retry-Scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody("Result")));

        // WireMock stub for simulating error state.
        // If ScenarioState is 'Fail State', it returns a http response with status 500 to simulate a service error.
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/test"))
                .inScenario("Retry-Scenario")
                .whenScenarioStateIs("Fail State")
                .willReturn(WireMock.aResponse()
                        .withStatus(500)));

        // WireMock stub for setting the state to 'Fail State' to simulate a service outage
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/setStateFail"))
                .inScenario("Retry-Scenario")
                .willSetStateTo("Fail State")
                .willReturn(WireMock.aResponse().withStatus(200)));

        // WireMock stub for setting the state to Scenario.STARTED to simulate a service recovery
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo("/setStateStarted"))
                .inScenario("Retry-Scenario")
                .willSetStateTo(Scenario.STARTED)
                .willReturn(WireMock.aResponse().withStatus(200)));
    }

    /**
     * Simulates a test execution to a call to the service.
     * Due to the resilience configuration it should pass, even if the service is down for a certain amount of time.
     * @throws InterruptedException
     *          InterruptedException
     */
    @Test
    public void testExecuteCall() throws InterruptedException {



        final List<Thread> threadList = prepareExecutionThreads();

        startStateChangerThread();

        // start all threads
        for(Thread thread : threadList) {
            thread.start();
        }

        // wait for all threads to finish
        for (Thread thread : threadList) {
            thread.join();
        }

    }

    /**
     * Preparation of threads that will call the WireMock stub.
     *
     * @return list of threads
     */
    private List<Thread> prepareExecutionThreads() {

        final List<Thread> threadList = new ArrayList<>();
        final RetryPolicy retryPolicy = getRetryPolicy();
        final CircuitBreaker circuitBreaker = getCircuitBreaker();

        for(int i = 0; i < numberOfThreads; i++) {

            final Thread thread = new Thread(() -> {
                try {
                    sleep(ThreadLocalRandom.current().nextLong(20000));

                final JerseyTestClient testClient = new JerseyTestClient();
                assertEquals(200, testClient
                        .executeCall("http://localhost:8089/test", retryPolicy, circuitBreaker).getStatus());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });

            threadList.add(thread);

        }

        return threadList;
    }

    /**
     * Utility method for starting the thread which is responsible for setting the WireMock state to simulate a service outage and recovery.
     */
    private void startStateChangerThread() {

        new Thread(() -> {
            try {
                sleep(ThreadLocalRandom.current().nextLong(2000));
                final JerseyTestClient testClientReset = new JerseyTestClient();
                testClientReset.executeCall("http://localhost:8089/setStateFail");
                sleep(40000);
                testClientReset.executeCall("http://localhost:8089/setStateStarted");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

    }

    /**
     * Get the retry policy for the test.
     * RetryPolicy handles Exceptions and HTTP status of 500 with unlimited retries and a backoff strategy, which will increase the delay in between retries over time.
     *
     * @return RetryPolicy
     */
    private RetryPolicy<Response> getRetryPolicy() {
        return new RetryPolicy<Response>()
                .handle(Exception.class)
                .handleResultIf((Response result) -> result.getStatus() == 500)
                .withMaxRetries(-1)
                .withBackoff(1, 30, ChronoUnit.SECONDS)
                .onRetry((x) -> out.println("Failure on thread " + currentThread().getName() + ". Retry: " + x.getAttemptCount()));

    }

    /**
     * Gets the CircuitBreaker for the test.
     * The CircuitBreaker will open when 2 out of 3 requests fail and wait 30 seconds before retrying to send a request to the service.
     *
     * @return CircuitBreaker
     */
    private  CircuitBreaker<Response> getCircuitBreaker() {
        return new CircuitBreaker<Response>()
                .withFailureThreshold(2, 3)
                .handleResultIf((Response response) -> response.getStatus() == 500)
                .withDelay(Duration.ofSeconds(10))
                .onOpen(() -> out.println("Circuit was open due to multiple errors."))
                .onHalfOpen(() -> out.println("Circuit is not half open. Retrying to get data from service."))
                .onClose(() -> out.println("Circuit is closed again. Continuing normal operation..."));
    }

}