package com.doopp.reactor.guice.publisher;

import com.doopp.reactor.guice.ApiGatewayDispatcher;
import com.doopp.reactor.guice.websocket.AbstractWebSocketServerHandle;
import com.doopp.reactor.guice.websocket.WebSocketServerHandle;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.websocketx.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class ApiGatewayPublisher {

    private ApiGatewayDispatcher apiGatewayDispatcher;

    private GatewayWsHandle gatewayWsHandle;

    public ApiGatewayPublisher(ApiGatewayDispatcher apiGatewayDispatcher) {
        this.apiGatewayDispatcher = apiGatewayDispatcher;
        this.gatewayWsHandle = new GatewayWsHandle();
    }

    public Mono<Object> sendResponse(HttpServerRequest req, HttpServerResponse resp, WebsocketPublisher websocketPublisher, Object requestAttribute) {

        URL insideUrl = this.apiGatewayDispatcher.getInsideUrl(req.uri());

        HttpClient httpClient = HttpClient.create()
            .headers(httpHeaders -> {
                // set headers
                HttpHeaders headers = req.requestHeaders();
                headers.forEach(action->{
                    if (!action.getKey().equals("Host")) {
                        httpHeaders.set(action.getKey(), action.getValue());
                    }
                });
                httpHeaders.set("Host", insideUrl.getHost());
                // set cookie
                req.cookies().forEach((charSequence, cookies)->{
                    StringBuilder cookieString = new StringBuilder();
                    for (Cookie cookie : cookies) {
                        if (!cookieString.toString().equals("")) {
                            cookieString.append("; ");
                        }
                        cookieString.append(cookie.name()).append("=").append(cookie.value());
                    }
                    httpHeaders.set("Cookie", cookieString.toString());
                });
            })
            .keepAlive(true)
            .tcpConfiguration(tcpClient ->
                tcpClient.option(ChannelOption.SO_KEEPALIVE, true)
            );

        if (req.requestHeaders().get("upgrade")!=null && req.requestHeaders().get("upgrade").equals("websocket")) {
            return websocketPublisher.sendMessage(req, resp, this.gatewayWsHandle, requestAttribute);
        }
        else if (req.method() == HttpMethod.POST || req.method() == HttpMethod.PUT) {
            HttpClient.RequestSender sender = (req.method() == HttpMethod.POST) ? httpClient.post() : httpClient.put();
            return req
                .receive()
                .aggregate()
                .flatMap(byteBuf ->
                    sender
                        .uri(insideUrl.toString())
                        .send(Mono.just(byteBuf.retain()))
                        .responseSingle((sResp, sMonoBf) -> {
                            resp.status(sResp.status()).headers(sResp.responseHeaders());
                            return sMonoBf;
                        })
                        .map(ByteBuf::retain)
                );
        }
        else if (req.method() == HttpMethod.DELETE) {
            return httpClient
                    .delete()
                    .uri(insideUrl.toString())
                    .responseSingle((sResp, sMonoBf) -> {
                        resp.status(sResp.status()).headers(sResp.responseHeaders());
                        return sMonoBf;
                    })
                    .map(ByteBuf::retain);
        }
        else {
            return httpClient
                    .get()
                    .uri(insideUrl.toString())
                    .responseSingle((sResp, sMonoBf) -> {
                        resp.status(sResp.status()).headers(sResp.responseHeaders());
                        return sMonoBf;
                    })
                    .map(ByteBuf::retain);
        }
    }

    public boolean checkRequest(HttpServerRequest req) {
        return true;
    }

    private class GatewayWsHandle extends AbstractWebSocketServerHandle {

    }
}
