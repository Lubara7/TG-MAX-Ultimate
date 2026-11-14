package io.github.lubara7.tgmax.max;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaxClientTest {
    private final MockWebServer server = new MockWebServer();
    private final MaxFrameCodec codec = new MaxFrameCodec();
    private MaxClient client;

    @After public void tearDown() throws Exception {
        if (client != null) client.close();
        server.shutdown();
    }

    private MaxClient start(WebSocketListener listener, long timeout) throws Exception {
        server.enqueue(new MockResponse().withWebSocketUpgrade(listener));
        server.start();
        client = new MaxClient(new OkHttpClient(),
                new MaxDevice("synthetic-device", "en", "Android", "1080x1920", "UTC", "TG-MAX-Test"),
                server.url("/websocket").toString(), timeout);
        return client;
    }

    @Test public void handshakeAndHttpHeaderUseTheSameProfileSnapshot() throws Exception {
        AtomicReference<String> initAgent = new AtomicReference<>();
        WebSocketListener listener = new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame frame = codec.decode(bytes.toByteArray());
                initAgent.set(Maps.string(Maps.object(Maps.object(frame.payload).get("userAgent")).get("headerUserAgent")));
                respond(ws, frame, Maps.of());
            }
        };
        server.enqueue(new MockResponse().withWebSocketUpgrade(listener).setHeadersDelay(300, TimeUnit.MILLISECONDS));
        server.start();
        MaxDevice device = new MaxDevice("0123456789abcdef", "en", "Android 14", "800x360 3.0x", "UTC", "browser-before");
        client = new MaxClient(new OkHttpClient(), device, server.url("/websocket").toString(), 2000);
        CompletableFuture<Void> connection = client.connect();
        okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        device.setUserAgent("browser-after");
        connection.get(5, TimeUnit.SECONDS);
        assertEquals("browser-before", request.getHeader("User-Agent"));
        assertEquals(request.getHeader("User-Agent"), initAgent.get());
    }

    @Test public void closingBeforeBrowserInitializationCompletesWaiters() throws Exception {
        server.start();
        MaxDevice device = new MaxDevice("0123456789abcdef", "en", "Android 14", "800x360 3.0x", "UTC", null);
        client = new MaxClient(new OkHttpClient(), device, server.url("/websocket").toString(), 2000);
        CompletableFuture<Void> connection = client.connect();
        assertFalse(connection.isDone());
        client.close();
        try { connection.get(1, TimeUnit.SECONDS); fail("Closed client kept waiting for a profile"); }
        catch (ExecutionException expected) { assertEquals("CLIENT_CLOSED", ((MaxException) root(expected)).code); }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void handshakePrecedesAuthAndPushCannotConsumeAResponse() throws Exception {
        AtomicInteger order = new AtomicInteger();
        CountDownLatch push = new CountDownLatch(1);
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (order.getAndIncrement() == 0) {
                    assertEquals(MaxOpcode.INIT, f.opcode);
                    respond(ws, f, Maps.of());
                } else if (f.opcode == MaxOpcode.AUTH_CAPTCHA) {
                    assertEquals("auth", Maps.object(f.payload).get("source"));
                    respond(ws, f, Maps.of());
                } else {
                    assertEquals(MaxOpcode.AUTH_REQUEST, f.opcode);
                    assertEquals("START_AUTH", Maps.object(f.payload).get("type"));
                    ws.send(ByteString.of(codec.encode(new MaxFrame(0, f.sequence, 128, Maps.of("synthetic", true)))));
                    respond(ws, f, Maps.of("token", "synthetic-challenge"));
                }
            }
        }, 2_000);
        c.addPushListener(f -> push.countDown());
        Map<String, Object> result = new MaxApi(c).requestCode("+19995550123", false).get(4, TimeUnit.SECONDS);
        assertEquals("synthetic-challenge", result.get("token"));
        assertTrue(push.await(2, TimeUnit.SECONDS));
        assertEquals("https://web.max.ru", server.takeRequest().getHeader("Origin"));
    }

    @Test public void messageTimeoutDoesNotResendTheMessage() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode == MaxOpcode.INIT) respond(ws, f, Maps.of());
                else sends.incrementAndGet();
            }
        }, 120);
        try {
            new MaxApi(c).sendText(101, "synthetic text", -1, true, null).get(3, TimeUnit.SECONDS);
            fail("Missing timeout");
        } catch (ExecutionException expected) { assertTrue(root(expected) instanceof MaxException); }
        assertEquals(1, sends.get());
    }

    @Test public void wrongOpcodeIsRejectedAndDisconnectFailsPendingRequests() throws Exception {
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode == MaxOpcode.INIT) respond(ws, f, Maps.of());
                else ws.send(ByteString.of(codec.encode(new MaxFrame(1, f.sequence, f.opcode + 1, Maps.of()))));
            }
        }, 2_000);
        try { c.request(49, Maps.of()).get(4, TimeUnit.SECONDS); fail("Mismatched response accepted"); }
        catch (ExecutionException expected) { assertEquals("PROTOCOL_ERROR:RESPONSE_OPCODE_MISMATCH", ((MaxException) root(expected)).code); }
        assertFalse(c.isConnected());
    }

    @Test public void closeCompletesPendingFutureAndRefusesReconnect() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode == MaxOpcode.INIT) respond(ws, f, Maps.of());
                else received.countDown();
            }
        }, 3_000);
        CompletableFuture<?> pending = c.request(49, Maps.of());
        assertTrue(received.await(3, TimeUnit.SECONDS));
        c.close();
        try { pending.get(2, TimeUnit.SECONDS); fail("Pending request not failed"); }
        catch (ExecutionException expected) { assertEquals("CLIENT_CLOSED", ((MaxException) root(expected)).code); }
        assertTrue(c.connect().isCompletedExceptionally());
    }

    @Test public void maxPasswordUsesTrackAndReturnsLoginTokenWithoutTelegramSrp() throws Exception {
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode == MaxOpcode.INIT) respond(ws, f, Maps.of());
                else {
                    assertEquals(115, f.opcode);
                    assertEquals(Maps.of("trackId", "synthetic-track", "password", "synthetic-password"), f.payload);
                    respond(ws, f, Maps.of("tokenAttrs", Maps.of("LOGIN", Maps.of("token", "synthetic-login-token"))));
                }
            }
        }, 2_000);
        Map<String, Object> result = new MaxApi(c).checkPassword("synthetic-track", "synthetic-password").get(4, TimeUnit.SECONDS);
        assertEquals("synthetic-login-token", Maps.object(Maps.object(result.get("tokenAttrs")).get("LOGIN")).get("token"));
    }

    @Test public void listingSessionsIsReadOnlyAndClosingRequiresSeparateRequest() throws Exception {
        java.util.List<Integer> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        MaxClient client = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame frame = codec.decode(bytes.toByteArray());
                if (frame.opcode == MaxOpcode.INIT) {
                    respond(ws, frame, Maps.of());
                    return;
                }
                requests.add(frame.opcode);
                assertEquals(Maps.of(), frame.payload);
                respond(ws, frame, Maps.of("sessions", java.util.Collections.emptyList()));
            }
        }, 2_000);
        MaxApi api = new MaxApi(client);
        assertTrue(api.sessions().get(4, TimeUnit.SECONDS).containsKey("sessions"));
        assertEquals(java.util.List.of(96), requests);
        api.terminateOtherSessions().get(4, TimeUnit.SECONDS);
        assertEquals(java.util.List.of(96, 97), requests);
    }

    @Test public void authWaitsForCaptchaAndSendsItsTokenOnlyToCodeRequest() throws Exception {
        java.util.List<Integer> opcodes = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch requested = new CountDownLatch(1);
        CompletableFuture<String> proof = new CompletableFuture<>();
        MaxClient client = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame frame = codec.decode(bytes.toByteArray());
                if (frame.opcode == MaxOpcode.INIT) { respond(ws, frame, Maps.of()); return; }
                opcodes.add(frame.opcode);
                if (frame.opcode == 224) {
                    assertEquals(Maps.of("source", "auth", "identifier", "+19995550123"), frame.payload);
                    respond(ws, frame, Maps.of("link", "https://captcha.vk.com/synthetic"));
                } else {
                    assertEquals(17, frame.opcode);
                    assertEquals("synthetic-proof", Maps.object(frame.payload).get("captchaToken"));
                    respond(ws, frame, Maps.of("token", "synthetic-code-token", "codeLength", 6));
                }
            }
        }, 2_000);
        CompletableFuture<Map<String, Object>> result = new MaxApi(client).requestCode("+19995550123", false, link -> {
            assertEquals("https://captcha.vk.com/synthetic", link);
            requested.countDown();
            return proof;
        });
        assertTrue(requested.await(3, TimeUnit.SECONDS));
        assertEquals(java.util.List.of(224), opcodes);
        assertFalse(result.isDone());
        proof.complete("synthetic-proof");
        assertEquals("synthetic-code-token", result.get(4, TimeUnit.SECONDS).get("token"));
        assertEquals(java.util.List.of(224, 17), opcodes);
    }

    @Test public void cancelledCaptchaNeverRequestsSms() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CompletableFuture<String> proof = new CompletableFuture<>();
        AtomicInteger sms = new AtomicInteger();
        MaxClient client = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame frame = codec.decode(bytes.toByteArray());
                if (frame.opcode == MaxOpcode.INIT) respond(ws, frame, Maps.of());
                else if (frame.opcode == 224) respond(ws, frame, Maps.of("link", "https://captcha.vk.com/synthetic"));
                else sms.incrementAndGet();
            }
        }, 2_000);
        CompletableFuture<Map<String, Object>> result = new MaxApi(client).requestCode("+19995550123", false, link -> {
            requested.countDown();
            return proof;
        });
        assertTrue(requested.await(3, TimeUnit.SECONDS));
        result.cancel(false);
        proof.handle((token, failure) -> null).get(3, TimeUnit.SECONDS);
        assertTrue(proof.isCancelled());
        assertFalse(proof.complete("late-proof"));
        assertEquals(0, sms.get());
    }

    @Test public void outgoingCallUsesServerTrackAndCorrectOpcodes() throws Exception {
        java.util.List<Integer> opcodes = new java.util.concurrent.CopyOnWriteArrayList<>();
        MaxClient client = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame request = codec.decode(bytes.toByteArray());
                if (request.opcode == MaxOpcode.INIT) { respond(ws, request, Maps.of()); return; }
                opcodes.add(request.opcode);
                assertEquals(Maps.of("trackId", "synthetic-call-track"), request.payload);
                if (request.opcode == 294) respond(ws, request, Maps.of("status", 1, "callNumber", "+19995550123"));
                else if (request.opcode == 291) respond(ws, request, Maps.of("passwordChallenge", Maps.of("trackId", "synthetic-password-track")));
                else fail("Unexpected call authentication opcode");
            }
        }, 2000);
        MaxApi api = new MaxApi(client);
        MaxCallOut.Check check = api.checkCall("synthetic-call-track").get(4, TimeUnit.SECONDS);
        assertEquals(1, check.status);
        assertEquals("+19995550123", check.callNumber);
        assertTrue(api.loginByTrack("synthetic-call-track").get(4, TimeUnit.SECONDS).containsKey("passwordChallenge"));
        assertEquals(java.util.List.of(294, 291), opcodes);
    }

    @Test public void ghostPolicySuppressesQueuedReceiptsTypingAndActiveStatusAtTheSocket() throws Exception {
        CountDownLatch init = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<WebSocket> socket = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<MaxFrame> handshake = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<MaxFrame> outbound = new java.util.concurrent.CopyOnWriteArrayList<>();
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode == MaxOpcode.INIT) { socket.set(ws); handshake.set(f); init.countDown(); }
                else { outbound.add(f); respond(ws, f, Maps.of()); }
            }
        }, 2000);
        CompletableFuture<?> queuedRead = c.request(MaxOpcode.READ, Maps.of("chatId", 10L));
        assertTrue(init.await(3, TimeUnit.SECONDS));
        c.setPrivacy(new MaxPrivacyPolicy(true, false, false));
        respond(socket.get(), handshake.get(), Maps.of());
        queuedRead.get(4, TimeUnit.SECONDS);
        new MaxApi(c).typing(10, false).get(4, TimeUnit.SECONDS);
        c.request(MaxOpcode.LOGIN, Maps.of("token", "synthetic", "interactive", true)).get(4, TimeUnit.SECONDS);
        c.request(MaxOpcode.SEND, Maps.of("chatId", 10L)).get(4, TimeUnit.SECONDS);
        assertEquals(java.util.List.of(MaxOpcode.LOGIN, MaxOpcode.SEND), outbound.stream().map(f -> f.opcode).collect(java.util.stream.Collectors.toList()));
        assertEquals(false, Maps.object(outbound.get(0).payload).get("interactive"));
        c.setPrivacy(MaxPrivacyPolicy.DEFAULT);
        c.request(MaxOpcode.READ, Maps.of("chatId", 10L)).get(4, TimeUnit.SECONDS);
        assertEquals(1, outbound.stream().filter(f -> f.opcode == MaxOpcode.READ).count());
    }

    @Test public void readAndTypingControlsAreIndependentAndBackgroundActivityStaysInactive() throws Exception {
        java.util.List<MaxFrame> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode != MaxOpcode.INIT) sent.add(f);
                respond(ws, f, Maps.of());
            }
        }, 2000);
        MaxApi api = new MaxApi(c);
        c.connect().get(4, TimeUnit.SECONDS);
        c.setPrivacy(new MaxPrivacyPolicy(false, true, false));
        api.markRead(10, 20, 30).get(4, TimeUnit.SECONDS);
        api.typing(10, false).get(4, TimeUnit.SECONDS);
        c.setPrivacy(new MaxPrivacyPolicy(false, false, true));
        api.typing(10, false).get(4, TimeUnit.SECONDS);
        api.markRead(10, 20, 30).get(4, TimeUnit.SECONDS);
        c.activity(false).get(4, TimeUnit.SECONDS);
        api.login("synthetic").get(4, TimeUnit.SECONDS);
        assertEquals(1, sent.stream().filter(f -> f.opcode == MaxOpcode.READ).count());
        assertEquals(1, sent.stream().filter(f -> f.opcode == MaxOpcode.TYPING).count());
        assertEquals("TEXT", Maps.object(sent.stream().filter(f -> f.opcode == MaxOpcode.TYPING).findFirst().get().payload).get("type"));
        assertEquals(false, Maps.object(sent.stream().filter(f -> f.opcode == MaxOpcode.LOGIN).findFirst().get().payload).get("interactive"));
    }

    @Test public void maxQrFlowUsesServerTrackAndSharedAuthenticationCompletion() throws Exception {
        java.util.List<Integer> opcodes = new java.util.concurrent.CopyOnWriteArrayList<>();
        MaxClient c = start(new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                MaxFrame f = codec.decode(bytes.toByteArray());
                if (f.opcode == MaxOpcode.INIT) { respond(ws, f, Maps.of()); return; }
                opcodes.add(f.opcode);
                if (f.opcode == 288) respond(ws, f, Maps.of("trackId", "synthetic-qr-track", "qrLink", "https://max.ru/qr?token=synthetic", "ttl", 120000, "pollingInterval", 1000));
                else {
                    assertEquals(Maps.of("trackId", "synthetic-qr-track"), f.payload);
                    if (f.opcode == 289) respond(ws, f, Maps.of("status", Maps.of("loginAvailable", true, "ttl", 10000)));
                    else respond(ws, f, Maps.of("passwordChallenge", Maps.of("trackId", "synthetic-password-track")));
                }
            }
        }, 2000);
        MaxApi api = new MaxApi(c);
        MaxQrLogin.Params qr = api.requestQr().get(4, TimeUnit.SECONDS);
        assertTrue(api.checkQr(qr.trackId).get(4, TimeUnit.SECONDS).loginAvailable);
        assertTrue(api.loginByTrack(qr.trackId).get(4, TimeUnit.SECONDS).containsKey("passwordChallenge"));
        assertEquals(java.util.List.of(288, 289, 291), opcodes);
    }

    private void respond(WebSocket ws, MaxFrame request, Object payload) {
        ws.send(ByteString.of(codec.encode(new MaxFrame(1, request.sequence, request.opcode, payload))));
    }

    private static Throwable root(Throwable e) {
        while ((e instanceof ExecutionException || e instanceof java.util.concurrent.CompletionException) && e.getCause() != null)
            e = e.getCause();
        return e;
    }
}
