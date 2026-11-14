package io.github.lubara7.tgmax.telegram;

import android.content.Context;
import android.os.Build;
import io.github.lubara7.tgmax.max.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.tgnet.tl.TL_account;

public final class TgMaxRuntime {
    private static final Map<Integer, TgMaxRuntime> INSTANCES = new ConcurrentHashMap<>();
    private final int account;
    private final MaxLocalState local;
    private final StableIds ids;
    private final TgMaxModels models;
    private final MaxClient client;
    private final MaxApi api;
    private final android.content.SharedPreferences privacyPreferences;
    private volatile MaxPrivacyPolicy privacy;
    private final ExecutorService executor;
    private final Map<Integer, CompletableFuture<?>> requests = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> guids = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> login;
    private volatile CompletableFuture<Void> allDialogs;
    private volatile Map<String, Object> loginData = Maps.of();
    private volatile String registrationToken;
    private volatile String sessionToken;
    private volatile String passwordTrack;
    private volatile String passwordHint = "";
    private volatile String authPhone = "";
    private volatile Function<String, CompletableFuture<String>> captchaHandler;

    public void preparePhoneAuth(String userAgent) {
        client.device().language = TgMaxLocales.networkCode(LocaleController.getLocaleStringIso639());
        if (userAgent != null && !userAgent.isEmpty()) {
            local.webUserAgent(userAgent);
            client.device().setUserAgent(userAgent);
        }
    }

    public void setCaptchaHandler(Function<String, CompletableFuture<String>> handler) { captchaHandler = handler; }
    public void clearCaptchaHandler(Function<String, CompletableFuture<String>> handler) {
        if (captchaHandler == handler) captchaHandler = null;
    }

    public static boolean enabled() { return true; }
    public boolean needsPassword() { return passwordTrack != null; }
    public String passwordHint() { return passwordHint; }
    public CompletableFuture<TLRPC.TL_auth_authorization> checkPassword(String password) {
        return api.checkPassword(passwordTrack, password).thenComposeAsync(data -> {
            String token = token(data, "LOGIN");
            if (token.isEmpty()) throw new MaxException("PASSWORD_HASH_INVALID");
            return authorize(token).thenApply(auth -> (TLRPC.TL_auth_authorization) auth);
        }, executor);
    }
    public static String errorText(Throwable failure) { return error(failure).text; }
    public static TgMaxRuntime get(int account) { return INSTANCES.computeIfAbsent(account, TgMaxRuntime::new); }
    public static int connectionState(int account) {
        TgMaxRuntime runtime = INSTANCES.get(account);
        return runtime != null && runtime.client.isConnected() ? 3 : 1;
    }

    private TgMaxRuntime(int account) {
        this.account = account;
        Context context = ApplicationLoader.applicationContext;
        privacyPreferences = context.getSharedPreferences("max_privacy_" + account, Context.MODE_PRIVATE);
        privacy = new MaxPrivacyPolicy(privacyPreferences.getBoolean("ghost", false),
                privacyPreferences.getBoolean("unread", false), privacyPreferences.getBoolean("noTyping", false));
        local = new MaxLocalState(context, account);
        ids = new StableIds(local);
        models = new TgMaxModels(ids);
        executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tgmax-account-" + account); t.setDaemon(true); return t; });
        android.util.DisplayMetrics display = context.getResources().getDisplayMetrics();
        String version = local.profileVersion(recommendedWebVersion(context));
        boolean legacy = local.legacyProfile();
        String screen = legacy ? display.widthPixels + "x" + display.heightPixels
                : String.format(java.util.Locale.US, "%.0fx%.0f %.1fx", display.heightPixels / display.density,
                        display.widthPixels / display.density, display.density);
        MaxDevice device = new MaxDevice(local.deviceId(), TgMaxLocales.networkCode(LocaleController.getLocaleStringIso639()),
                legacy ? "Android " + Build.VERSION.RELEASE : "Android", screen, TimeZone.getDefault().getID(), local.webUserAgent(),
                legacy ? MaxSessionProfile.legacyWeb(version) : MaxSessionProfile.web(version, null),
                legacy ? TgMaxLocales.networkCode(LocaleController.getLocaleStringIso639()) : deviceLanguage());
        client = new MaxClient(new OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build(), device);
        client.setPrivacy(privacy);
        api = new MaxApi(client);
        client.addPushListener(frame -> executor.execute(() -> {
            try { push(frame); }
            catch (RuntimeException failure) { allDialogs = null; }
        }));
        client.addConnectionListener(connected -> {
            ConnectionsManager.onConnectionStateChanged(connected ? 3 : 1, account);
            if (!connected) { login = null; allDialogs = null; }
        });
        if (!device.ready().isDone()) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                String agent = android.webkit.WebSettings.getDefaultUserAgent(context);
                local.webUserAgent(agent);
                device.setUserAgent(agent);
            } catch (RuntimeException failure) { device.userAgentUnavailable(); }
        });
    }

    private static String recommendedWebVersion(Context context) {
        try (java.io.InputStream stream = context.getAssets().open("tgmax-session-profiles.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = stream.read(block)) != -1) {
                if (bytes.size() + count > 65536) throw new MaxException("MAX_PROFILE_CATALOG_INVALID");
                bytes.write(block, 0, count);
            }
            org.json.JSONObject catalog = new org.json.JSONObject(bytes.toString("UTF-8"));
            org.json.JSONObject profile = catalog.getJSONObject("profiles").getJSONObject(catalog.getString("activeProfile"));
            if (!"WEB".equals(profile.getString("deviceType")) || !"WEBSOCKET".equals(profile.getString("transport")))
                throw new MaxException("MAX_TRANSPORT_PROFILE_MISMATCH");
            return profile.getString("appVersion");
        } catch (Exception failure) { throw new MaxException("MAX_PROFILE_CATALOG_INVALID", failure); }
    }

    private static String deviceLanguage() {
        android.os.LocaleList locales = android.content.res.Resources.getSystem().getConfiguration().getLocales();
        String language = locales.isEmpty() ? "en" : locales.get(0).getLanguage();
        return language.isEmpty() ? "en" : language;
    }

    public MaxPrivacyPolicy privacy() { return privacy; }
    public void privacy(MaxPrivacyPolicy policy) {
        privacy = policy;
        client.setPrivacy(policy);
        privacyPreferences.edit().putBoolean("ghost", policy.ghost).putBoolean("unread", policy.unread)
                .putBoolean("noTyping", policy.noTyping).apply();
    }

    public String avatarUrl(long userId) { return models.avatarUrl(userId); }
    public String chatAvatarUrl(long chatId) { return models.chatAvatarUrl(chatId); }
    public CompletableFuture<MaxQrLogin.Params> requestQr() {
        passwordTrack = null;
        authPhone = "";
        return api.requestQr();
    }
    public CompletableFuture<MaxQrLogin.Status> checkQr(String track) { return api.checkQr(track); }

    public void send(int token, TLObject request, RequestDelegate callback, RequestDelegateTimestamp timestamp,
                     QuickAckDelegate quickAck, WriteToSocketDelegate written) {
        CompletableFuture<TLObject> result = new CompletableFuture<>();
        requests.put(token, result);
        executor.execute(() -> {
            if (result.isCancelled()) return;
            try {
                CompletableFuture<TLObject> operation = dispatch(request);
                result.whenComplete((v, e) -> { if (result.isCancelled()) operation.cancel(false); });
                operation.whenComplete((v, e) -> {
                    if (e == null) result.complete(v); else result.completeExceptionally(e);
                });
            } catch (RuntimeException e) { result.completeExceptionally(e); }
        });
        result.whenComplete((response, failure) -> {
            requests.remove(token);
            guids.remove(token);
            if (result.isCancelled()) return;
            TLRPC.TL_error error = failure == null ? null : error(failure);
            Utilities.stageQueue.postRunnable(() -> {
                if (written != null && failure == null) written.run();
                if (quickAck != null && failure == null) quickAck.run();
                if (callback != null) callback.run(response, error);
                else if (timestamp != null) timestamp.run(response, error, System.currentTimeMillis());
                else if (response instanceof TLRPC.Updates)
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            });
        });
    }

    public void cancel(int token) {
        CompletableFuture<?> result = requests.remove(token);
        if (result != null) result.cancel(false);
        guids.remove(token);
    }

    public void bind(int token, int guid) { guids.put(token, guid); }
    public void cancelGuid(int guid) { guids.forEach((token, value) -> { if (value == guid) cancel(token); }); }

    public void cleanup(boolean reset) {
        requests.keySet().forEach(this::cancel);
        if (reset) {
            privacyPreferences.edit().clear().apply();
            local.clear();
            client.close();
            executor.shutdownNow();
            INSTANCES.remove(account, this);
        }
    }

    private CompletableFuture<TLObject> dispatch(TLObject request) {
        if (request instanceof TLRPC.TL_auth_sendCode) {
            TLRPC.TL_auth_sendCode r = (TLRPC.TL_auth_sendCode) request;
            passwordTrack = null;
            passwordHint = "";
            authPhone = phone(r.phone_number);
            return requestCode(phone(r.phone_number), false);
        }
        if (request instanceof TLRPC.TL_auth_resendCode) {
            TLRPC.TL_auth_resendCode r = (TLRPC.TL_auth_resendCode) request;
            return requestCode(phone(r.phone_number), true);
        }
        if (request instanceof TLRPC.TL_auth_signIn) {
            TLRPC.TL_auth_signIn r = (TLRPC.TL_auth_signIn) request;
            return api.verifyCode(r.phone_code, r.phone_code_hash).thenComposeAsync(this::acceptVerification, executor);
        }

        if (request instanceof TLRPC.TL_auth_signUp) {
            TLRPC.TL_auth_signUp r = (TLRPC.TL_auth_signUp) request;
            if (registrationToken == null) throw new MaxException("PHONE_CODE_EXPIRED");
            return api.register(registrationToken, r.first_name, r.last_name).thenComposeAsync(data -> {
                String token = token(data, "LOGIN");
                if (token.isEmpty()) token = Maps.string(data.get("token"));
                if (token.isEmpty()) throw new MaxException("MAX_LOGIN_TOKEN_MISSING");
                registrationToken = null;
                return authorize(token);
            }, executor);
        }
        return ensureLogin().thenComposeAsync(unused -> authenticated(request), executor);
    }

    private CompletableFuture<TLObject> authenticated(TLObject request) {
        if (request instanceof TLRPC.TL_messages_setTyping) {
            TLRPC.TL_messages_setTyping r = (TLRPC.TL_messages_setTyping) request;
            return api.typing(serverChat(r.peer), r.action instanceof TLRPC.TL_sendMessageCancelAction)
                    .thenApply(data -> new TLRPC.TL_boolTrue());
        }
        if (request instanceof TL_account.updateStatus) {
            return client.activity(!((TL_account.updateStatus) request).offline).thenApply(data -> new TLRPC.TL_boolTrue());
        }
        if (request instanceof TL_account.getAuthorizations) {
            return api.sessions().thenApply(TgMaxModels::sessions);
        }
        if (request instanceof TLRPC.TL_auth_resetAuthorizations) {
            return api.terminateOtherSessions().thenApply(data -> new TLRPC.TL_boolTrue());
        }
        if (request instanceof TLRPC.TL_messages_getDialogs) {
            TLRPC.TL_messages_getDialogs r = (TLRPC.TL_messages_getDialogs) request;
            if (r.offset_id != 0) return done(new TLRPC.TL_messages_dialogs());
            return loadDialogs().thenApplyAsync(unused -> finish(models.dialogs()), executor);
        }
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory r = (TLRPC.TL_messages_getHistory) request;
            long chat = serverChat(r.peer);
            long from = r.offset_id == 0 ? System.currentTimeMillis() + 86_400_000L : ids.message(r.offset_id).time - 1;
            return api.history(chat, from, r.limit).thenApplyAsync(data -> finish(models.history(chat, data)), executor);
        }
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage r = (TLRPC.TL_messages_sendMessage) request;
            if (r.schedule_date != 0 || (r.entities != null && !r.entities.isEmpty())) throw new MaxException("MAX_TEXT_ONLY");
            long chat = serverChat(r.peer);
            Long reply = r.reply_to == null || r.reply_to.reply_to_msg_id == 0 ? null : ids.message(r.reply_to.reply_to_msg_id).server;
            return api.sendText(chat, r.message, r.random_id, !r.silent, reply)
                    .thenApplyAsync(data -> finish(sent(chat, data, r.random_id)), executor);
        }
        if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage r = (TLRPC.TL_messages_editMessage) request;
            if (r.entities != null && !r.entities.isEmpty()) throw new MaxException("MAX_TEXT_ONLY");
            StableIds.MessageId id = ids.message(r.id);
            long chat = serverChat(r.peer);
            if (chat != id.chat) throw new MaxException("MAX_MESSAGE_PEER_MISMATCH");
            return api.edit(chat, id.server, r.message).thenComposeAsync(data -> api.history(chat, id.time + 1, 10), executor)
                    .thenApplyAsync(data -> finish(edited(chat, data, id.local)), executor);
        }
        if (request instanceof TLRPC.TL_channels_deleteMessages) {
            TLRPC.TL_channels_deleteMessages r = (TLRPC.TL_channels_deleteMessages) request;
            return delete(r.id, true);
        }
        if (request instanceof TLRPC.TL_messages_deleteMessages) {
            TLRPC.TL_messages_deleteMessages r = (TLRPC.TL_messages_deleteMessages) request;
            return delete(r.id, r.revoke);
        }
        if (request instanceof TLRPC.TL_messages_readHistory) {
            TLRPC.TL_messages_readHistory r = (TLRPC.TL_messages_readHistory) request;
            return read(serverChat(r.peer), r.max_id);
        }
        if (request instanceof TLRPC.TL_channels_readHistory) {
            TLRPC.TL_channels_readHistory r = (TLRPC.TL_channels_readHistory) request;
            return read(ids.serverChat(-r.channel.channel_id), r.max_id).thenApply(v -> new TLRPC.TL_boolTrue());
        }
        if (request instanceof TLRPC.TL_messages_getFullChat) {
            TLRPC.TL_messages_getFullChat r = (TLRPC.TL_messages_getFullChat) request;
            return fullChat(ids.serverChat(-r.chat_id));
        }
        if (request instanceof TLRPC.TL_channels_getFullChannel) {
            TLRPC.TL_channels_getFullChannel r = (TLRPC.TL_channels_getFullChannel) request;
            return fullChat(ids.serverChat(-r.channel.channel_id));
        }
        if (request instanceof TLRPC.TL_channels_getChannels) {
            TLRPC.TL_channels_getChannels r = (TLRPC.TL_channels_getChannels) request;
            TLRPC.TL_messages_chats response = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel c : r.id) response.chats.add(models.chat(models.requireChat(ids.serverChat(-c.channel_id))));
            return done(finish(response));
        }
        if (request instanceof TLRPC.TL_contacts_getContacts) {
            TLRPC.TL_contacts_contacts response = new TLRPC.TL_contacts_contacts();
            response.users.addAll(models.users());
            return done(finish(response));
        }
        if (request instanceof TLRPC.TL_users_getUsers) {
            TLRPC.TL_users_getUsers r = (TLRPC.TL_users_getUsers) request;
            List<Long> rawIds = new ArrayList<>();
            for (TLRPC.InputUser u : r.id) rawIds.add(u instanceof TLRPC.TL_inputUserSelf ? models.self : ids.maxUser(u.user_id));
            return api.contacts(rawIds).thenApplyAsync(data -> {
                models.snapshot(data);
                Vector<TLRPC.User> response = new Vector<>(null);
                for (long id : rawIds) response.objects.add(models.user(id));
                return finish(response);
            }, executor);
        }
        if (request instanceof TLRPC.TL_channels_joinChannel) {
            TLRPC.TL_channels_joinChannel r = (TLRPC.TL_channels_joinChannel) request;
            Map<String, Object> raw = models.requireChat(ids.serverChat(-r.channel.channel_id));
            String link = Maps.string(raw.get("link"));
            if (link.isEmpty()) throw new MaxException("MAX_CHANNEL_LINK_MISSING");
            return api.join(link).thenApplyAsync(data -> {
                models.snapshot(Maps.of("chats", Collections.singletonList(data.get("chat"))));
                return finish(new TLRPC.TL_updates());
            }, executor);
        }
        if (request instanceof TLRPC.TL_channels_leaveChannel) {
            TLRPC.TL_channels_leaveChannel r = (TLRPC.TL_channels_leaveChannel) request;
            return api.leave(ids.serverChat(-r.channel.channel_id)).thenApply(data -> new TLRPC.TL_updates());
        }
        if (request instanceof TLRPC.TL_updates_getState) {
            TLRPC.TL_updates_state state = new TLRPC.TL_updates_state();
            state.date = now(); state.pts = ids.cursor("global", false);
            return done(state);
        }
        if (request instanceof TLRPC.TL_updates_getDifference) {
            TLRPC.TL_updates_differenceEmpty difference = new TLRPC.TL_updates_differenceEmpty();
            difference.date = now();
            return done(difference);
        }
        if (request instanceof TLRPC.TL_auth_logOut) {
            return api.logout().handleAsync((data, failure) -> {
                local.clear();
                return new TLRPC.TL_auth_loggedOut();
            }, executor);
        }
        throw new MaxException("MAX_UNSUPPORTED_" + request.getClass().getSimpleName());
    }

    private synchronized CompletableFuture<Void> ensureLogin() {
        if (login != null) return login;
        login = CompletableFuture.supplyAsync(local::session, executor).thenCompose(saved -> {
            if (saved == null) throw new MaxException("AUTH_KEY_UNREGISTERED");
            return performLogin(saved[1]);
        });
        login.whenComplete((unused, error) -> { if (error != null) login = null; });
        return login;
    }

    public CompletableFuture<MaxCallOut.Check> checkCall(String trackId) { return api.checkCall(trackId); }
    public CompletableFuture<TLObject> finishCall(String trackId) {
        return api.loginByTrack(trackId).thenComposeAsync(this::acceptVerification, executor);
    }
    public CompletableFuture<TLObject> refreshPhoneCode() { return requestCode(authPhone, true); }

    private CompletableFuture<TLObject> acceptVerification(Map<String, Object> data) {
        if (data.get("passwordChallenge") != null) {
            Map<String, Object> challenge = Maps.object(data.get("passwordChallenge"));
            passwordTrack = Maps.string(challenge.get("trackId"));
            passwordHint = Maps.string(challenge.get("hint"));
            if (passwordTrack.isEmpty()) { passwordTrack = null; throw new MaxException("MAX_AUTH_TRACK_MISSING"); }
            throw new MaxException("MAX_SESSION_PASSWORD_NEEDED");
        }
        String token = token(data, "LOGIN");
        if (token.isEmpty()) {
            registrationToken = token(data, "REGISTER");
            if (registrationToken.isEmpty()) throw new MaxException("MAX_LOGIN_TOKEN_MISSING");
            return CompletableFuture.completedFuture(new TLRPC.TL_auth_authorizationSignUpRequired());
        }
        return authorize(token);
    }

    public static final class CallSentCode extends TLRPC.TL_auth_sentCode {
        public final MaxCallOut.Params call;
        private CallSentCode(MaxCallOut.Params call) { this.call = call; }
    }

    private CompletableFuture<TLObject> requestCode(String phone, boolean resend) {
        Function<String, CompletableFuture<String>> handler = captchaHandler;
        CompletableFuture<Map<String, Object>> operation = handler == null
                ? api.requestCode(phone, resend) : api.requestCode(phone, resend, handler);
        CompletableFuture<TLObject> result = operation.thenApplyAsync(this::sentCode, executor);
        result.whenComplete((value, failure) -> { if (result.isCancelled()) operation.cancel(false); });
        return result;
    }

    private TLObject sentCode(Map<String, Object> data) {
        MaxAuthChallenge challenge = new MaxAuthChallenge(data);
        if (challenge.call != null) return new CallSentCode(challenge.call);
        TLRPC.TL_auth_sentCode response = new TLRPC.TL_auth_sentCode();
        TLRPC.TL_auth_sentCodeTypeSms type = new TLRPC.TL_auth_sentCodeTypeSms();
        type.length = challenge.codeLength;
        response.type = type;
        response.phone_code_hash = challenge.token;
        response.next_type = new TLRPC.TL_auth_codeTypeSms();
        response.timeout = challenge.resendSeconds;
        response.flags = 6;
        return response;
    }

    private CompletableFuture<TLObject> authorize(String token) {
        return performLogin(token).thenApplyAsync(unused -> {
            TLRPC.TL_auth_authorization auth = new TLRPC.TL_auth_authorization();
            auth.user = models.user(models.self);
            if (!authPhone.isEmpty()) auth.user.phone = authPhone.replace("+", "");
            if (!auth.user.phone.isEmpty()) auth.user.flags |= 16;
            passwordTrack = null;
            passwordHint = "";
            return finish(auth);
        }, executor);
    }

    private CompletableFuture<Void> performLogin(String token) {
        return api.login(token).thenAcceptAsync(data -> {
            models.snapshot(data);
            if (models.self == 0) throw new MaxException("MAX_PROFILE_MISSING");
            sessionToken = Maps.string(data.get("token"));
            if (sessionToken.isEmpty()) sessionToken = token;
            local.session(models.self, sessionToken);
            ids.flush();
            loginData = data;
            login = CompletableFuture.completedFuture(null);
            allDialogs = null;
        }, executor);
    }

    private synchronized CompletableFuture<Void> loadDialogs() {
        if (allDialogs == null) allDialogs = page(Maps.number(loginData.get("chatMarker"), 0), 0)
                .thenComposeAsync(unused -> {
                    models.dialogs();
                    return hydrateContacts(models.missingContacts(), 0);
                }, executor);
        return allDialogs;
    }

    private CompletableFuture<Void> hydrateContacts(List<Long> missing, int offset) {
        if (offset >= missing.size()) return CompletableFuture.completedFuture(null);
        int end = Math.min(missing.size(), offset + 100);
        return api.contacts(new ArrayList<>(missing.subList(offset, end))).handleAsync((data, failure) -> {
            if (failure == null) models.snapshot(data);
            return null;
        }, executor).thenComposeAsync(unused -> hydrateContacts(missing, end), executor);
    }

    private CompletableFuture<Void> page(long marker, int pages) {
        if (marker <= 0) return CompletableFuture.completedFuture(null);
        if (pages >= 200) throw new MaxException("MAX_CHAT_PAGINATION_LIMIT");
        return api.chats(marker, 50).thenComposeAsync(data -> {
            models.snapshot(data);
            long next = Maps.number(data.get("marker"), 0);
            List<Map<String, Object>> chats = Maps.objects(data.get("chats"));
            if (chats.size() < 50 || next <= 0 || next == marker) return CompletableFuture.completedFuture(null);
            return page(next, pages + 1);
        }, executor);
    }

    private TLObject sent(long chat, Map<String, Object> data, long randomId) {
        Map<String, Object> raw = Maps.object(data.get("message"));
        if (raw.isEmpty()) throw new MaxException("MAX_SEND_RESPONSE_MISSING_MESSAGE");
        TLRPC.Message message = models.message(chat, raw);
        TL_update.TL_updateMessageID id = new TL_update.TL_updateMessageID(); id.id = message.id; id.random_id = randomId;
        TLRPC.TL_updates updates = update(message, false); updates.updates.add(0, id);
        return updates;
    }

    private TLObject edited(long chat, Map<String, Object> data, int localId) {
        TLRPC.messages_Messages messages = models.history(chat, data);
        for (TLRPC.Message message : messages.messages) if (message.id == localId) return update(message, true);
        throw new MaxException("MAX_EDIT_RESULT_NOT_FOUND");
    }

    private TLRPC.TL_updates update(TLRPC.Message message, boolean edit) {
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        TLRPC.Update update;
        boolean channel = message.peer_id.channel_id != 0;
        int pts = models.pts(message.dialog_id, true);
        if (edit && channel) {
            TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
            u.message = message; u.pts = pts; u.pts_count = 1; update = u;
        } else if (edit) {
            TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
            u.message = message; u.pts = pts; u.pts_count = 1; update = u;
        } else if (channel) {
            TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
            u.message = message; u.pts = pts; u.pts_count = 1; update = u;
        } else {
            TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
            u.message = message; u.pts = pts; u.pts_count = 1; update = u;
        }
        result.updates.add(update); result.users.addAll(models.users());
        if (message.dialog_id < 0) result.chats.add(models.chat(models.requireChat(ids.serverChat(message.dialog_id))));
        result.date = now();
        return result;
    }

    private CompletableFuture<TLObject> delete(ArrayList<Integer> localIds, boolean everyone) {
        if (localIds.isEmpty()) return done(affected(0, false));
        long chat = ids.message(localIds.get(0)).chat;
        List<Long> serverIds = new ArrayList<>();
        for (int id : localIds) {
            StableIds.MessageId entry = ids.message(id);
            if (entry.chat != chat) throw new MaxException("MAX_DELETE_MULTIPLE_PEERS_UNSUPPORTED");
            serverIds.add(entry.server);
        }
        return api.delete(chat, serverIds, everyone).thenApplyAsync(data -> finish(affected(chat, true)), executor);
    }

    private CompletableFuture<TLObject> read(long chat, int localId) {
        if (localId <= 0 || localId == Integer.MAX_VALUE) return done(affected(chat, false));
        StableIds.MessageId id = ids.message(localId);
        if (id.chat != chat) throw new MaxException("MAX_MESSAGE_PEER_MISMATCH");
        return api.markRead(chat, id.server, id.time).thenApply(data -> affected(chat, false));
    }

    private TLRPC.TL_messages_affectedMessages affected(long chat, boolean advance) {
        TLRPC.TL_messages_affectedMessages result = new TLRPC.TL_messages_affectedMessages();
        result.pts = ids.cursor("global", advance); result.pts_count = advance ? 1 : 0;
        return result;
    }

    private CompletableFuture<TLObject> fullChat(long chat) {
        return api.chatInfo(chat).thenApplyAsync(data -> {
            models.snapshot(data);
            Map<String, Object> raw = models.requireChat(chat);
            TLRPC.TL_messages_chatFull result = new TLRPC.TL_messages_chatFull();
            if ("CHANNEL".equals(raw.get("type"))) {
                TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
                full.id = ids.chat(chat); full.pts = models.pts(-full.id, false);
                result.full_chat = full;
            } else {
                TLRPC.TL_chatFull full = new TLRPC.TL_chatFull();
                full.id = ids.chat(chat); full.participants = new TLRPC.TL_chatParticipants();
                full.participants.chat_id = full.id;
                result.full_chat = full;
            }
            result.full_chat.about = Maps.string(raw.get("description"));
            result.full_chat.notify_settings = new TLRPC.TL_peerNotifySettings();
            result.full_chat.chat_photo = new TLRPC.TL_photoEmpty();
            result.full_chat.exported_invite = new TLRPC.TL_chatInviteExported();
            result.chats.add(models.chat(raw)); result.users.addAll(models.users());
            return finish(result);
        }, executor);
    }

    private void push(MaxFrame frame) {
        if (models.self == 0) return;
        Map<String, Object> data = Maps.object(frame.payload);
        if (frame.opcode == MaxOpcode.MESSAGE) {
            long chat = Maps.number(data.get("chatId"), 0);
            Map<String, Object> raw = Maps.object(data.get("message"));
            if (chat == 0 || raw.isEmpty()) return;
            CompletableFuture<Map<String, Object>> ensure = models.chats.containsKey(chat)
                    ? CompletableFuture.completedFuture(Maps.of()) : api.chatInfo(chat);
            ensure.thenAcceptAsync(info -> {
                models.snapshot(info);
                TLRPC.TL_updates updates = finish(update(models.message(chat, raw), false));
                Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processUpdates(updates, false));
            }, executor);
        } else if (frame.opcode == MaxOpcode.CHAT_UPDATE) {
            models.snapshot(Maps.of("chats", Collections.singletonList(data.get("chat"))));
            ids.flush();
        }
    }

    private long serverChat(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerSelf) return ids.serverChat(ids.user(models.self));
        return ids.serverChat(TgMaxModels.dialogId(peer));
    }

    private <T extends TLObject> T finish(T result) { ids.flush(); return result; }
    private static CompletableFuture<TLObject> done(TLObject value) { return CompletableFuture.completedFuture(value); }
    private static String phone(String value) { return "+" + value.replaceAll("[^0-9]", ""); }
    private static String token(Map<String, Object> data, String kind) {
        return Maps.string(Maps.object(Maps.object(data.get("tokenAttrs")).get(kind)).get("token"));
    }
    private static int now() { return (int) (System.currentTimeMillis() / 1000); }
    private static TLRPC.TL_error error(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException || failure instanceof java.util.concurrent.ExecutionException))
            failure = failure.getCause();
        String code = failure instanceof MaxException ? ((MaxException) failure).code : "MAX_INTERNAL_ERROR";
        TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400;
        error.text = code.startsWith("MAX_") || code.startsWith("PHONE_") || code.equals("AUTH_KEY_UNREGISTERED") ? code : "MAX_" + code;
        return error;
    }
}
