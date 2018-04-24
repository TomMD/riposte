package com.nike.riposte.server.componenttest;

import com.nike.riposte.client.asynchttp.netty.StreamingAsyncHttpClient;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.http.impl.SimpleProxyRouterEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.org.lidalia.slf4jext.Level;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;
import io.restassured.response.ExtractableResponse;

import static com.nike.riposte.server.componenttest.VerifyResponseHttpStatusCodeHandlingRfcCorrectnessComponentTest.BackendEndpoint.ATTEMPTED_CONTENT_LENGTH_LIE_VALUE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyResponseHttpStatusCodeHandlingRfcCorrectnessComponentTest.BackendEndpoint.ATTEMPTED_RESPONSE_PAYLOAD_SIZE_HEADER_KEY;
import static com.nike.riposte.server.componenttest.VerifyResponseHttpStatusCodeHandlingRfcCorrectnessComponentTest.BackendEndpoint.CALL_ID_RESPONSE_HEADER_KEY;
import static com.nike.riposte.server.testutils.ComponentTestUtils.generatePayload;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaders.Names.TRANSFER_ENCODING;
import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static io.restassured.config.RedirectConfig.redirectConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that HTTP status codes are handled appropriately as per the RFC.
 * In particular, this verifies that the must-never-have-a-payload responses work through streaming proxy/router endpoints without chunking the response.
 * We put this through a backend server returning a normal response, followed two levels of proxy routing.
 *
 * <p>See https://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html and https://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html.
 */
@RunWith(DataProviderRunner.class)
public class VerifyResponseHttpStatusCodeHandlingRfcCorrectnessComponentTest {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static Server backendServer;
    private static ServerConfig backendServerConfig;

    private static Server intermediateRouterServer;
    private static ServerConfig intermediateRouterServerConfig;

    private static Server edgeRouterServer;
    private static ServerConfig edgeRouterServerConfig;

    private static Level logPrintLevelAtStart;

    @BeforeClass
    public static void setUpClass() throws Exception {
        // The slf4j-test logger causes a huge amount of spam to be output for these tests. Disable for these tests, then re-enable in tearDown().
        logPrintLevelAtStart = TestLoggerFactory.getInstance().getPrintLevel();
        TestLoggerFactory.getInstance().setPrintLevel(Level.WARN);

        System.setProperty(StreamingAsyncHttpClient.SHOULD_LOG_BAD_MESSAGES_AFTER_REQUEST_FINISHES_SYSTEM_PROP_KEY, "true");

        int backendPort = ComponentTestUtils.findFreePort();
        backendServerConfig = new BackendServerConfig(backendPort);
        backendServer = new Server(backendServerConfig);
        backendServer.startup();

        int intermediateRouterPort = ComponentTestUtils.findFreePort();
        intermediateRouterServerConfig = new RouterServerConfig(intermediateRouterPort, backendPort);
        intermediateRouterServer = new Server(intermediateRouterServerConfig);
        intermediateRouterServer.startup();

        int edgeRouterPort = ComponentTestUtils.findFreePort();
        edgeRouterServerConfig = new RouterServerConfig(edgeRouterPort, intermediateRouterPort);
        edgeRouterServer = new Server(edgeRouterServerConfig);
        edgeRouterServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (logPrintLevelAtStart != null)
            TestLoggerFactory.getInstance().setPrintLevel(logPrintLevelAtStart);

        backendServer.shutdown();
        intermediateRouterServer.shutdown();
        edgeRouterServer.shutdown();
    }

    private enum CallScenario {
        EMPTY_PAYLOAD_VIA_ROUTER(true, true),
        EMPTY_PAYLOAD_VIA_BACKEND(true, false),
        NON_EMPTY_PAYLOAD_VIA_ROUTER(false, true),
        NON_EMPTY_PAYLOAD_VIA_BACKEND(false, false);

        public final boolean shouldReturnEmptyPayload;
        public final boolean shouldCallViaRouter;

        CallScenario(boolean shouldReturnEmptyPayload, boolean shouldCallViaRouter) {
            this.shouldReturnEmptyPayload = shouldReturnEmptyPayload;
            this.shouldCallViaRouter = shouldCallViaRouter;
        }
    }

    @DataProvider
    public static List<List<Object>> responseStatusCodeScenariosDataProvider() {
        // We don't need to test *ALL* possibilities, just the extended list (http://www.iana.org/assignments/http-status-codes/http-status-codes.xhtml)
        //      plus a few unassigned ones.
        List<Integer> statusCodesToTest = IntStream
            .rangeClosed(200, 999) // No good way to test codes less than 200 at the moment.
            .filter(val -> (val <= 230 || val >= 300))
            .filter(val -> (val <= 320 || val >= 400))
            .filter(val -> (val <= 435 || val == 451 || val >= 500))
            .filter(val -> (val <= 520 || val >= 600))
            .filter(val -> (val <= 610 || val >= 700))
            .filter(val -> val <= 710)
            .boxed()
            .collect(Collectors.toList());

        List<List<Object>> data = new ArrayList<>(statusCodesToTest.size() * 2);
        statusCodesToTest.forEach(statusCode -> {
            for (CallScenario callScenario : CallScenario.values()) {
                data.add(Arrays.asList(statusCode, callScenario));
            }
        });

        return data;
    }

    private boolean isContentAlwaysEmptyStatusCode(int statusCode) {
        if (statusCode >= 100 && statusCode < 200)
            return true;

        switch(statusCode) {
            case 204:
            case 205:
            case 304:
                return true;
        }

        return false;
    }

    /**
     * content-length header should never be returned for status codes 1xx and 204
     * https://tools.ietf.org/html/rfc7230#section-3.3.2
     */
    private boolean isContentLengthHeaderShouldBeMissingStatusCode(int statusCode) {

        if (statusCode >= 100 && statusCode < 200)
            return true;
        else if (statusCode == 204) {
            return true;
        }

        return false;
    }

    /**
     * Some scenarios are allowed to lie about content-length,
     * see https://tools.ietf.org/html/rfc7230#section-3.3.2
     */
    private boolean isAllowedToLieAboutContentLengthStatusCode(int desiredStatusCode) {
        switch (desiredStatusCode) {
            case 304:
                return true;
            default:
                return false;
        }
    }

    @Test
    @UseDataProvider("responseStatusCodeScenariosDataProvider")
    public void verify_response_status_code_scenarios(int desiredStatusCode, CallScenario callScenario) {
        boolean shouldReturnEmptyPayload = callScenario.shouldReturnEmptyPayload;
        int portToCall = (callScenario.shouldCallViaRouter)
                         ? edgeRouterServerConfig.endpointsPort()
                         : backendServerConfig.endpointsPort();

        for (int i = 0; i < 3; i++) { // Run this scenario 3 times in quick succession to catch potential keep-alive connection pooling issues.
            logger.info("=== RUN {} ===", i);
            String callId = UUID.randomUUID().toString();
            ExtractableResponse response = given()
                .config(config().redirect(redirectConfig().followRedirects(false)))
                .baseUri("http://localhost")
                .port(portToCall)
                .basePath(BackendEndpoint.MATCHING_PATH)
                .header(BackendEndpoint.DESIRED_RESPONSE_HTTP_STATUS_CODE_HEADER_KEY, String.valueOf(desiredStatusCode))
                .header(BackendEndpoint.SHOULD_RETURN_EMPTY_PAYLOAD_BODY_HEADER_KEY, String.valueOf(shouldReturnEmptyPayload))
                .header(BackendEndpoint.CALL_ID_REQUEST_HEADER_KEY, callId)
            .when()
                .get()
            .then()
                .extract();

            assertThat(response.statusCode()).isEqualTo(desiredStatusCode);
            assertThat(response.header(CALL_ID_RESPONSE_HEADER_KEY)).isEqualTo(callId);
            assertThat(response.header(ATTEMPTED_RESPONSE_PAYLOAD_SIZE_HEADER_KEY))
                .isNotEqualTo(response.header(ATTEMPTED_CONTENT_LENGTH_LIE_VALUE_HEADER_KEY));
            assertThat(response.header(TRANSFER_ENCODING)).isNull();

            if (isContentAlwaysEmptyStatusCode(desiredStatusCode)) {
                assertThat(response.asString()).isNullOrEmpty();
                if (isContentLengthHeaderShouldBeMissingStatusCode(desiredStatusCode)) {
                    assertThat(response.header(CONTENT_LENGTH)).isNull();
                }
                else if (isAllowedToLieAboutContentLengthStatusCode(desiredStatusCode)) {
                    assertThat(response.header(CONTENT_LENGTH))
                        .isEqualTo(response.header(ATTEMPTED_CONTENT_LENGTH_LIE_VALUE_HEADER_KEY));
                }
                else {
                    assertThat(response.header(CONTENT_LENGTH)).isEqualTo("0");
                }
            }
            else {
                // Not an always-empty-payload status code. Content length should equal actual payload size.
                assertThat(response.header(CONTENT_LENGTH))
                    .isEqualTo(response.header(ATTEMPTED_RESPONSE_PAYLOAD_SIZE_HEADER_KEY));

                if (shouldReturnEmptyPayload) {
                    assertThat(response.asString()).isNullOrEmpty();
                    assertThat(response.header(CONTENT_LENGTH)).isEqualTo("0");
                }
                else {
                    String expectedPayload = callId + BackendEndpoint.NON_EMPTY_PAYLOAD;
                    assertThat(response.asString()).isEqualTo(expectedPayload);
                    assertThat(response.header(CONTENT_LENGTH)).isEqualTo(String.valueOf(expectedPayload.length()));
                }
            }
            logger.info("=== END RUN {} ===", i);
        }
    }

    public static class RouterServerConfig implements ServerConfig {
        private final int myPort;
        private final Endpoint<?> proxyEndpoint;

        public RouterServerConfig(int myPort, int downstreamPort) {
            this.myPort = myPort;

            this.proxyEndpoint = new SimpleProxyRouterEndpoint(
                Matcher.match(BackendEndpoint.MATCHING_PATH, HttpMethod.GET),
                "localhost",
                downstreamPort,
                BackendEndpoint.MATCHING_PATH,
                false,
                Optional.empty(),
                true);
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return Collections.singleton(proxyEndpoint);
        }


        @Override
        public int endpointsPort() {
            return myPort;
        }

        @Override
        public long workerChannelIdleTimeoutMillis() {
            return -1;
        }
    }

    public static class BackendServerConfig implements ServerConfig {
        private final int port;
        private final Endpoint<?> backendEndpoint;

        public BackendServerConfig(int port) {
            this.port = port;
            this.backendEndpoint = new BackendEndpoint();
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return Collections.singleton(backendEndpoint);
        }

        @Override
        public int endpointsPort() {
            return port;
        }

        @Override
        public long workerChannelIdleTimeoutMillis() {
            return -1;
        }
    }

    public static class BackendEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/backendEndpoint";
        public static final String DESIRED_RESPONSE_HTTP_STATUS_CODE_HEADER_KEY = "desiredResponseHttpStatusCode";
        public static final String SHOULD_RETURN_EMPTY_PAYLOAD_BODY_HEADER_KEY = "shouldReturnEmptyPayloadBody";
        public static final String CALL_ID_REQUEST_HEADER_KEY = "callId";
        public static final String CALL_ID_RESPONSE_HEADER_KEY = "callId-received";
        public static final String ATTEMPTED_RESPONSE_PAYLOAD_SIZE_HEADER_KEY = "attempted-response-payload-size";
        public static final String ATTEMPTED_CONTENT_LENGTH_LIE_VALUE_HEADER_KEY = "attempted-content-length-lie-value";
        public static final String NON_EMPTY_PAYLOAD = generatePayload(1000);

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            int statusCode = Integer.parseInt(request.getHeaders().get(DESIRED_RESPONSE_HTTP_STATUS_CODE_HEADER_KEY));
            boolean returnEmptyPayload = "true".equals(request.getHeaders().get(SHOULD_RETURN_EMPTY_PAYLOAD_BODY_HEADER_KEY));
            String callIdReceived = String.valueOf(request.getHeaders().get(CALL_ID_REQUEST_HEADER_KEY));
            String returnPayload = (returnEmptyPayload) ? null : callIdReceived + NON_EMPTY_PAYLOAD;
            int actualContentLength = (returnPayload == null) ? 0 : returnPayload.getBytes(CharsetUtil.UTF_8).length;
            int attemptedContentLengthLieValue = actualContentLength + 42;

            return CompletableFuture.completedFuture(
                ResponseInfo.newBuilder(returnPayload)
                            .withHttpStatusCode(statusCode)
                            .withDesiredContentWriterMimeType("text/plain")
                            .withHeaders(
                                new DefaultHttpHeaders()
                                    .set(CALL_ID_RESPONSE_HEADER_KEY, callIdReceived)
                                    .set(ATTEMPTED_RESPONSE_PAYLOAD_SIZE_HEADER_KEY, actualContentLength)
                                    .set(ATTEMPTED_CONTENT_LENGTH_LIE_VALUE_HEADER_KEY, attemptedContentLengthLieValue)
                                    .set(CONTENT_LENGTH, attemptedContentLengthLieValue)
                            )
                            // Disable compression, or else the server will change content-length on us when it gzips.
                            .withPreventCompressedOutput(true)
                            .build()
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.GET);
        }
    }
}
