package com.nike.riposte.server.componenttest;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.ApiErrorWithMetadata;
import com.nike.internal.util.Pair;
import com.nike.riposte.server.Server;
import com.nike.riposte.server.config.ServerConfig;
import com.nike.riposte.server.http.Endpoint;
import com.nike.riposte.server.http.ProxyRouterEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.server.http.StandardEndpoint;
import com.nike.riposte.server.testutils.ComponentTestUtils;
import com.nike.riposte.util.Matcher;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nike.backstopper.apierror.sample.SampleCoreApiError.MALFORMED_REQUEST;
import static com.nike.riposte.server.testutils.ComponentTestUtils.executeRequest;
import static com.nike.riposte.server.testutils.ComponentTestUtils.verifyErrorReceived;
import static com.nike.riposte.util.AsyncNettyHelper.supplierWithTracingAndMdc;
import static java.lang.Thread.sleep;
import static java.util.Collections.singleton;

public class VerifyDecoderFailedResultIsHandledTest {

    private static Server proxyServer;
    private static ServerConfig proxyServerConfig;
    private static Server downstreamServer;
    private static ServerConfig downstreamServerConfig;
    private static final int incompleteCallTimeoutMillis = 5000;

    @BeforeClass
    public static void setUpClass() throws Exception {
        downstreamServerConfig = new DownstreamServerTestConfig();
        downstreamServer = new Server(downstreamServerConfig);
        downstreamServer.startup();

        proxyServerConfig = new ProxyTestingTestConfig();
        proxyServer = new Server(proxyServerConfig);
        proxyServer.startup();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        proxyServer.shutdown();
        downstreamServer.shutdown();
    }

    @Test
    public void proxy_endpoints_should_handle_decode_exception() throws Exception {
        // given
        int payloadSize = 10000;
        ByteBuf payload = ComponentTestUtils.createByteBufPayload(payloadSize);

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, RouterEndpoint.MATCHING_PATH, payload
        );

        //leave off content-length and transfer-encoding to trigger DecoderFailedResult
        request.headers().set(HttpHeaders.Names.HOST, "localhost");

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, proxyServerConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertErrorResponse(serverResponse);
    }

    @Test
    public void standardEndpoint_should_handle_decode_exception() throws Exception {
        // given
        int payloadSize = 100000;
        ByteBuf payload = ComponentTestUtils.createByteBufPayload(payloadSize);

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, DownstreamEndpoint.MATCHING_PATH, payload
        );

        //leave off content-length and transfer-encoding to trigger DecoderFailedResult
        request.headers().set(HttpHeaders.Names.HOST, "localhost");

        // when
        Pair<Integer, String> serverResponse = executeRequest(request, downstreamServerConfig.endpointsPort(), incompleteCallTimeoutMillis);

        // then
        assertErrorResponse(serverResponse);
    }

    private void assertErrorResponse(Pair<Integer, String> serverResponse) throws IOException {
        ApiError expectedApiError = new ApiErrorWithMetadata(MALFORMED_REQUEST, Pair.of("cause", "Invalid HTTP request"));
        verifyErrorReceived(serverResponse.getRight(), serverResponse.getLeft(), expectedApiError);
    }

    private static class DownstreamEndpoint extends StandardEndpoint<Void, String> {

        public static final String MATCHING_PATH = "/downstreamEndpoint";
        public static final String RESPONSE_PAYLOAD = "basic-endpoint-" + UUID.randomUUID().toString();

        @Override
        public CompletableFuture<ResponseInfo<String>> execute(RequestInfo<Void> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx) {
            //need to do some work in a future to force the DecoderException to bubble up and return a 400
            return CompletableFuture.supplyAsync(supplierWithTracingAndMdc(() -> {
                try {
                    sleep(10);
                } catch (InterruptedException e) {
                }
                return null;
            }, ctx), longRunningTaskExecutor).thenApply(o -> ResponseInfo.newBuilder(RESPONSE_PAYLOAD).build());
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH, HttpMethod.POST);
        }
    }

    public static class RouterEndpoint extends ProxyRouterEndpoint {

        public static final String MATCHING_PATH = "/proxyEndpoint";
        private final int downstreamPort;

        public RouterEndpoint(int downstreamPort) {
            this.downstreamPort = downstreamPort;
        }

        @Override
        public CompletableFuture<DownstreamRequestFirstChunkInfo> getDownstreamRequestFirstChunkInfo(RequestInfo<?> request,
                                                                                                     Executor longRunningTaskExecutor,
                                                                                                     ChannelHandlerContext ctx) {
            return CompletableFuture.completedFuture(
                    new DownstreamRequestFirstChunkInfo(
                            "127.0.0.1", downstreamPort, false,
                            generateSimplePassthroughRequest(request, DownstreamEndpoint.MATCHING_PATH, request.getMethod(), ctx)
                    )
            );
        }

        @Override
        public Matcher requestMatcher() {
            return Matcher.match(MATCHING_PATH);
        }
    }

    public static class DownstreamServerTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public DownstreamServerTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new DownstreamEndpoint());
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

    }

    public static class ProxyTestingTestConfig implements ServerConfig {
        private final int port;
        private final Collection<Endpoint<?>> endpoints;

        public ProxyTestingTestConfig() {
            try {
                port = ComponentTestUtils.findFreePort();
            } catch (IOException e) {
                throw new RuntimeException("Couldn't allocate port", e);
            }

            endpoints = singleton(new RouterEndpoint(downstreamServerConfig.endpointsPort()));
        }

        @Override
        public Collection<Endpoint<?>> appEndpoints() {
            return endpoints;
        }

        @Override
        public int endpointsPort() {
            return port;
        }

    }
}
