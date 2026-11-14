package io.github.lubara7.tgmax.max;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class MaxClient implements Closeable {
    public static final String ENDPOINT = "wss://api.oneme.ru/websocket";
    private final OkHttpClient http;
    private final MaxDevice device;
    private final String endpoint;
    private final long timeoutMillis;
    private final MaxFrameCodec codec = new MaxFrameCodec();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tgmax-protocol"); t.setDaemon(true); return t;
    });
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<MaxFrame>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Object lock = new Object();
    private volatile WebSocket socket;
    private volatile CompletableFuture<Void> connecting;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile MaxPrivacyPolicy privacy = MaxPrivacyPolicy.DEFAULT;
    private volatile boolean interactive = true;
    private volatile Map<String, Object> handshake = Maps.of();
    private final CompletableFuture<Void> closedSignal = new CompletableFuture<>();

    public MaxClient(OkHttpClient http, MaxDevice device) { this(http, device, ENDPOINT, 30_000); }

    MaxClient(OkHttpClient http, MaxDevice device, String endpoint, long timeoutMillis) {
        this.http = http;
        this.device = device;
        this.endpoint = endpoint;
        this.timeoutMillis = timeoutMillis;
        scheduler.scheduleWithFixedDelay(() -> {
            if (ready) rawRequest(MaxOpcode.PING, Maps.of("interactive", interactive));
        }, 30, 30, TimeUnit.SECONDS);
    }

    public CompletableFuture<Void> connect() {
        return CompletableFuture.anyOf(device.ready(), closedSignal).thenCompose(unused -> connectPrepared());
    }

    private CompletableFuture<Void> connectPrepared() {
        synchronized (lock) {
            if (closed) return failed(new MaxException("CLIENT_CLOSED"));
            if (ready) return CompletableFuture.completedFuture(null);
            if (connecting != null) return connecting;
            final Map<String, Object> init = device.handshake();
            CompletableFuture<Void> attempt = new CompletableFuture<>();
            connecting = attempt;
            try {
                socket = http.newWebSocket(new Request.Builder().url(endpoint)
                    .header("Origin", "https://web.max.ru").header("User-Agent", Maps.string(Maps.object(init.get("userAgent")).get("headerUserAgent"))).build(),
                    new WebSocketListener() {
                        @Override public void onOpen(WebSocket ws, Response response) {
                            synchronized (lock) {
                                if (connecting != attempt || closed) { ws.cancel(); return; }
                                socket = ws;
                            }
                            rawRequest(MaxOpcode.INIT, init).whenComplete((value, error) -> {
                                if (error != null) { disconnected(ws, new MaxException("HANDSHAKE_FAILED", error)); return; }
                                synchronized (lock) {
                                    if (connecting != attempt || closed) return;
                                    handshake = Maps.object(value);
                                    ready = true;
                                    connecting = null;
                                    attempt.complete(null);
                                }
                                connectionListeners.forEach(l -> l.accept(true));
                            });
                        }

                        @Override public void onMessage(WebSocket ws, ByteString bytes) {
                            if (ws != socket) return;
                            try { receive(codec.decode(bytes.toByteArray())); }
                            catch (RuntimeException e) {
                                String detail = e instanceof MaxException ? ((MaxException) e).code : "INVALID_RESPONSE";
                                disconnected(ws, new MaxException("PROTOCOL_ERROR:" + detail, e));
                            }
                        }

                        @Override public void onMessage(WebSocket ws, String text) {
                            disconnected(ws, new MaxException("UNEXPECTED_TEXT_FRAME"));
                        }

                        @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                            disconnected(ws, new MaxException("NETWORK", t));
                        }

                        @Override public void onClosed(WebSocket ws, int code, String reason) {
                            disconnected(ws, new MaxException("NETWORK_CLOSED"));
                        }

                        @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, null); }
                    });
            } catch (RuntimeException failure) {
                connecting = null;
                attempt.completeExceptionally(new MaxException("CONNECT_SETUP_FAILED", failure));
            }
            return attempt;
        }
    }

    public Map<String, Object> handshakeData() { return handshake; }
    public MaxDevice device() { return device; }
    public CompletableFuture<Map<String, Object>> activity(boolean value) {
        interactive = value;
        return request(MaxOpcode.PING, Maps.of("interactive", value));
    }
    public synchronized void setPrivacy(MaxPrivacyPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("privacy");
        privacy = policy;
        if (ready) rawRequest(MaxOpcode.PING, Maps.of("interactive", interactive && policy.sendsPresence()));
    }
    public boolean isConnected() { return ready; }
    public void addPushListener(Consumer<MaxFrame> listener) { listeners.add(listener); }
    public void addConnectionListener(Consumer<Boolean> listener) { connectionListeners.add(listener); }

    public CompletableFuture<Map<String, Object>> request(int opcode, Map<String, Object> payload) {
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        CompletableFuture<Void> connection = connect();
        connection.whenComplete((unused, error) -> {
            if (result.isCancelled()) return;
            if (error != null) { result.completeExceptionally(error); return; }
            CompletableFuture<Object> raw = rawRequest(opcode, payload);
            result.whenComplete((v, e) -> { if (result.isCancelled()) raw.cancel(false); });
            raw.whenComplete((value, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else if (value == null || value instanceof Map) result.complete(Maps.object(value));
                else result.completeExceptionally(new MaxException("EXPECTED_MAP"));
            });
        });
        return result;
    }

    private synchronized CompletableFuture<Object> rawRequest(int opcode, Object payload) {
        MaxPrivacyPolicy policy = privacy;
        if (!policy.allows(opcode)) return CompletableFuture.completedFuture(Maps.of());
        if ((opcode == MaxOpcode.PING || opcode == MaxOpcode.LOGIN) && payload instanceof Map) {
            Map<String, Object> protectedPayload = new java.util.LinkedHashMap<>(Maps.object(payload));
            protectedPayload.put("interactive", interactive && policy.sendsPresence() && Boolean.TRUE.equals(protectedPayload.get("interactive")));
            payload = protectedPayload;
        }
        Pending request;
        int seq;
        synchronized (pending) {
            if (closed || socket == null) return failed(new MaxException("NOT_CONNECTED"));
            if (pending.size() >= 2048) return failed(new MaxException("TOO_MANY_REQUESTS"));
            do { seq = sequence.updateAndGet(i -> i >= Short.MAX_VALUE ? 1 : i + 1); } while (pending.containsKey(seq));
            request = new Pending(opcode);
            pending.put(seq, request);
        }
        final int key = seq;
        request.result.whenComplete((v, e) -> {
            pending.remove(key, request);
            ScheduledFuture<?> timeout = request.timeout;
            if (timeout != null) timeout.cancel(false);
        });
        try {
            request.timeout = scheduler.schedule(() -> {
                Pending p = pending.remove(key);
                if (p != null) p.result.completeExceptionally(new MaxException("TIMEOUT"));
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            if (request.result.isDone()) {
                request.timeout.cancel(false);
                return request.result;
            }
            WebSocket current = socket;
            if (current == null || !current.send(ByteString.of(codec.encode(new MaxFrame(0, seq, opcode, payload)))))
                request.result.completeExceptionally(new MaxException("NETWORK_WRITE"));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            request.result.completeExceptionally(new MaxException("CLIENT_CLOSED", e));
        } catch (RuntimeException e) { request.result.completeExceptionally(e); }
        return request.result;
    }

    private void receive(MaxFrame frame) {
        if (frame.command == 0) {
            if (frame.opcode == MaxOpcode.PING) {
                WebSocket current = socket;
                if (current != null) current.send(ByteString.of(codec.encode(new MaxFrame(1, frame.sequence, frame.opcode, null))));
            } else listeners.forEach(l -> l.accept(frame));
            return;
        }
        Pending p = pending.get(frame.sequence);
        if (p == null) return;
        if (p.opcode != frame.opcode) throw new MaxException("RESPONSE_OPCODE_MISMATCH");
        if (frame.command == 1) p.result.complete(frame.payload);
        else {
            String code = Maps.string(Maps.object(frame.payload).get("error"));
            if (code.isEmpty()) code = Maps.string(Maps.object(frame.payload).get("message"));
            if (!code.matches("[A-Za-z0-9_.:-]{1,100}")) code = "SERVER_ERROR";
            p.result.completeExceptionally(new MaxException(code));
        }
    }

    private void disconnected(WebSocket ws, MaxException error) {
        synchronized (lock) {
            if (socket != ws) return;
            ready = false;
            socket = null;
            CompletableFuture<Void> attempt = connecting;
            connecting = null;
            pending.values().forEach(p -> p.result.completeExceptionally(error));
            pending.clear();
            if (attempt != null) attempt.completeExceptionally(error);
        }
        ws.cancel();
        connectionListeners.forEach(l -> l.accept(false));
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            closedSignal.completeExceptionally(new MaxException("CLIENT_CLOSED"));
            WebSocket ws = socket;
            if (ws != null) disconnected(ws, new MaxException("CLIENT_CLOSED"));
            scheduler.shutdownNow();
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable e) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(e); return result;
    }

    private static final class Pending {
        final int opcode;
        final CompletableFuture<Object> result = new CompletableFuture<>();
        ScheduledFuture<?> timeout;
        Pending(int opcode) { this.opcode = opcode; }
    }
}
