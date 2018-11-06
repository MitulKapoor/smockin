package com.smockin.mockserver.proxy;

import com.smockin.admin.dto.HttpClientCallDTO;
import com.smockin.admin.dto.response.HttpClientResponseDTO;
import com.smockin.admin.persistence.enums.RestMethodEnum;
import com.smockin.admin.websocket.MockLogFeedHandler;
import com.smockin.mockserver.dto.MockServerState;
import com.smockin.mockserver.dto.ProxyActiveMock;
import com.smockin.mockserver.engine.BaseServerEngine;
import com.smockin.mockserver.exception.MockServerException;
import com.smockin.utils.GeneralUtils;
import com.smockin.utils.LiveLoggingUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.apache.http.HttpStatus;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

@Service
public class ProxyServer implements BaseServerEngine<Integer[], List<ProxyActiveMock>> {

    private final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    private HttpProxyServer httpProxyServer;
    private final Object monitor = new Object();
    private MockServerState serverState = new MockServerState(false, 0);

    @Autowired
    private ProxyServerUtils proxyServerUtils;

    @Autowired
    private MockLogFeedHandler mockLogFeedHandler;


    @Override
    public void start(final Integer[] ports, final List<ProxyActiveMock> activeMocks) {

        try {

            final int proxyPort = ports[0];
            final int mockServerPort = ports[1];

            httpProxyServer = DefaultHttpProxyServer.bootstrap()
                    .withPort(proxyPort)
                    .withManInTheMiddle(new SmockinSelfSignedMitmManager())
                    .withFiltersSource(new HttpFiltersSourceAdapter() {

                        @Override
                        public int getMaximumRequestBufferSizeInBytes() {
                            return 512 * 1024;
                        }

                        public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                            logger.debug("filterRequest called");

                            final LittleProxyContext context = new LittleProxyContext(GeneralUtils.generateUUID());

                            return new HttpFiltersAdapter(originalRequest) {

                                @Override
                                public HttpResponse proxyToServerRequest(HttpObject httpObject) {
                                    logger.debug("proxyToServerRequest called");

                                    if (httpObject instanceof FullHttpRequest) {

                                        final FullHttpRequest request = (FullHttpRequest) httpObject;

                                        try {

                                            if (originalRequest.getDecoderResult().isFailure())
                                                return proxyServerUtils.buildBadResponse();

                                            proxyServerUtils.debugInboundRequest(originalRequest);

                                            if (proxyServerUtils.excludeInboundMethod(originalRequest.method().name()))
                                                return null;

                                            final Optional<ProxyActiveMock> mock = proxyServerUtils.findMockMatch(originalRequest, activeMocks);

                                            if (!mock.isPresent())
                                                return null;

                                            context.setUseMock(true);
                                            context.setUserCtx(mock.get().getUserCtx());
                                            context.getRequestHeaders().addAll(originalRequest.headers().entries());

                                            if (request.method() == HttpMethod.POST)
                                                context.setRequestBody(request.content().toString(CharsetUtil.UTF_8));

                                        } catch (MalformedURLException e) {
                                            logger.error("Error parsing inbound URL", e);
                                        } catch (IOException e) {
                                            logger.error("Error using mock substitute", e);
                                        }

                                    }

                                    return null;
                                }

                                @Override
                                public HttpObject proxyToClientResponse(final HttpObject httpObject) {
                                    logger.debug("proxyToClientResponse called");

                                    // Re-use response if already retrieved from mock server
                                    if (context.getMockedClientResponse() != null) {
                                        return context.getMockedClientResponse();
                                    }

                                    if (!context.getLittleProxyLoggingDTO().isLoggingRequestReceived()) {
                                        mockLogFeedHandler.broadcast(LiveLoggingUtils.buildLiveLogInboundEntry(context.getRequestId(), originalRequest.method().name(), originalRequest.uri(), originalRequest.headers().get("Content-Type"), context.getRequestBody(), true));
                                        context.getLittleProxyLoggingDTO().setLoggingRequestReceived(true);
                                    }

                                    if (httpObject instanceof DefaultFullHttpResponse) {

                                        final DefaultFullHttpResponse response = (DefaultFullHttpResponse) httpObject;

                                        if (response.status().code() == 502) {
                                            mockLogFeedHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundEntry(context.getRequestId(), response.status().code(), response.headers().get("Content-Type"), null, true, false));
                                            return httpObject;
                                        }

                                    }

                                    proxyServerUtils.debugInboundRequest(originalRequest);

                                    if (!context.isUseMock()) {

                                        if (httpObject instanceof DefaultHttpResponse) {

                                            final DefaultHttpResponse response = (DefaultHttpResponse)httpObject;
                                            context.getLittleProxyLoggingDTO().setLoggingResponseStatus(response.status().code());
                                            context.getLittleProxyLoggingDTO().setLoggingResponseContentType(response.headers().get("Content-Type"));

                                        } else if (httpObject instanceof DefaultHttpContent) {

                                            final DefaultHttpContent response = (DefaultHttpContent)httpObject;
                                            mockLogFeedHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundEntry(context.getRequestId(), context.getLittleProxyLoggingDTO().getLoggingResponseStatus(), context.getLittleProxyLoggingDTO().getLoggingResponseContentType(), response.content().toString(Charset.defaultCharset()), true, false));

                                        }

                                        return httpObject;
                                    }

                                    try {

                                        final URL inboundUrl = new URL(proxyServerUtils.fixProtocolWithDummyPrefix(originalRequest.uri()));
                                        final RestMethodEnum inboundMethod = RestMethodEnum.findByName(originalRequest.method().name());
                                        final HttpClientCallDTO dto = proxyServerUtils.buildRequestDTO(context, inboundMethod, proxyServerUtils.buildMockUrl(inboundUrl, mockServerPort, context.getUserCtx()));

                                        // Store response from mock server for re-use
                                        final HttpClientResponseDTO response = proxyServerUtils.callMock(dto);

                                        if (response.getStatus() == HttpStatus.SC_TEMPORARY_REDIRECT) {
                                            return httpObject;
                                        }

                                        context.setMockedClientResponse(proxyServerUtils.buildResponse(response));

                                        mockLogFeedHandler.broadcast(LiveLoggingUtils.buildLiveLogOutboundEntry(context.getRequestId(), response.getStatus(), response.getContentType(), response.getBody(), true, true));

                                        return context.getMockedClientResponse();

                                    } catch (MalformedURLException e) {
                                        logger.error("Error parsing inbound URL", e);
                                    } catch (IOException e) {
                                        logger.error("Error using mock substitute", e);
                                    }

                                    return httpObject;
                                }
                            };
                        }
                    })
                    .start();

            synchronized (monitor) {
                serverState.setRunning(true);
                serverState.setPort(proxyPort);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    @Override
    public void shutdown() {

        try {

            synchronized (monitor) {

                if (httpProxyServer == null || !serverState.isRunning()) {
                    return;
                }

                httpProxyServer.stop();
                serverState.setRunning(false);
            }

        } catch (Throwable ex) {
            throw new MockServerException(ex);
        }

    }

    @Override
    public MockServerState getCurrentState() {
        synchronized (monitor) {
            return serverState;
        }
    }

}
