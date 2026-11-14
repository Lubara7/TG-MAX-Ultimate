#!/usr/bin/env python3
import argparse
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_PREFIX = "io.github.lubara7.tgmax.telegram."


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def change(path, old, new, count=1):
    text = path.read_text()
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f"Upstream changed: {path.name}: expected {count} occurrences, found {actual}")
    path.write_text(text.replace(old, new))


def add_after_opening(path, signature, code):
    text = path.read_text()
    match = re.search(signature, text)
    if match is None:
        raise RuntimeError(f"Upstream method missing in {path}")
    position = text.index("{", match.start()) + 1
    path.write_text(text[:position] + "\n" + code + text[position:])


def guard_mtproto(target):
    java = target / "TMessagesProj/src/main/java"
    connection = java / "org/telegram/tgnet/ConnectionsManager.java"
    original = connection.read_text()
    signature = re.search(r"private void sendRequestInternal\(([^)]*)\)", original)
    callback = re.search(r"\bRequestDelegate\s+(\w+)", signature[1]) if signature else None
    if callback is None:
        raise RuntimeError("ConnectionsManager request callback signature changed")
    declarations = list(re.finditer(r"public static native (\w+) (native_\w+)\(([^;]*)\);", original))
    if len(declarations) < 35:
        raise RuntimeError("ConnectionsManager native API changed")
    local_only = {"native_setJava", "native_isGoodPrime", "native_test_AuthAuthorization"}
    names = {m[2] for m in declarations} - local_only
    declaration_text = {}
    masked = original
    for i, m in reversed(list(enumerate(declarations))):
        marker = f"TGMAX_NATIVE_DECLARATION_{i}"
        declaration_text[marker] = m[0]
        masked = masked[:m.start()] + marker + masked[m.end():]
    for name in names:
        masked = re.sub(r"\b" + name + r"\s*\(", "tgmax_" + name + "(", masked)
    for marker, declaration in declaration_text.items():
        masked = masked.replace(marker, declaration)
    connection.write_text(masked)
    for path in (target / "TMessagesProj/src").rglob("*"):
        if path == connection or path.suffix not in (".java", ".kt"):
            continue
        text = path.read_text()
        for name in names:
            text = re.sub(r"(ConnectionsManager(?:\.getInstance\([^()]*\))?\s*\.\s*)" + name + r"\s*\(", r"\1tgmax_" + name + "(", text)
            if re.search(r"import\s+(?:static\s+)?org\.telegram\.tgnet\.ConnectionsManager\." + name + r"\b", text):
                text = re.sub(r"\b" + name + r"\b", "tgmax_" + name, text)
        if text != path.read_text():
            path.write_text(text)
    wrappers = []
    runtime = JAVA_PREFIX + "TgMaxRuntime"
    for declaration in declarations:
        result, name, parameters = declaration[1], declaration[2], declaration[3]
        if name not in names:
            continue
        body = {"void": "return;", "int": "return 0;", "long": "return 0L;", "boolean": "return false;"}[result]
        if name == "native_sendRequest": body = 'throw new IllegalStateException("MTProto is disabled in TG-MAX");'
        if name == "native_getCurrentTime": body = "return (int) (System.currentTimeMillis() / 1000);"
        if name == "native_getCurrentTimeMillis": body = "return System.currentTimeMillis();"
        if name == "native_getConnectionState": body = f"return {runtime}.connectionState(currentAccount);"
        if name == "native_cancelRequest": body = f"{runtime}.get(currentAccount).cancel(token);"
        if name == "native_failNotRunningRequest": body = f"{runtime}.get(currentAccount).cancel(token);"
        if name == "native_cancelRequestsForGuid": body = f"{runtime}.get(currentAccount).cancelGuid(guid);"
        if name == "native_bindRequestToGuid": body = f"{runtime}.get(currentAccount).bind(requestToken, guid);"
        if name == "native_cleanUp": body = f"{runtime}.get(currentAccount).cleanup(resetKeys);"
        if name == "native_checkProxy": body = "if (requestTimeDelegate != null) requestTimeDelegate.run(-1); return 0L;"
        wrappers.append(f"    public static {result} tgmax_{name}({parameters}) {{ {body} }}")
    text = connection.read_text()
    position = text.rfind("}")
    connection.write_text(text[:position] + "\n" + "\n".join(wrappers) + "\n" + text[position:])
    add_after_opening(connection, r"private void sendRequestInternal\([^)]*\)", f"""        if ({runtime}.enabled()) {{
            {runtime}.get(currentAccount).send(requestToken, object, {callback[1]},
                    onCompleteTimestamp, onQuickAck, onWriteToSocket);
            return;
        }}
""")
    return names


def restrict_languages(target):
    base = target / "TMessagesProj/src/main"
    controller = base / "java/org/telegram/messenger/LocaleController.java"
    helper = JAVA_PREFIX + "TgMaxLocales"
    change(controller, "        systemDefaultLocale = Locale.getDefault();", f"        {helper}.install(this);\n        systemDefaultLocale = Locale.getDefault();")
    change(controller, "currentInfo = getLanguageFromDict(systemDefaultLocale.getLanguage());",
           f"currentInfo = getLanguageFromDict({helper}.code(systemDefaultLocale.toLanguageTag()));")
    change(controller, "currentInfo = getLanguageFromDict(lang);",
           f"currentInfo = getLanguageFromDict({helper}.code(lang));")
    add_after_opening(controller, r"public void loadRemoteLanguages\(final int currentAccount, boolean applyCurrent\)",
                      f"        if ({JAVA_PREFIX}TgMaxRuntime.enabled()) {{ {helper}.install(this); return; }}\n")
    add_after_opening(controller, r"private void loadOtherLanguages\(\)",
                      f"        if ({JAVA_PREFIX}TgMaxRuntime.enabled()) return;\n")
    allowed = {"ru", "en", "es", "uz", "pt", "fr", "be"}
    removed = []
    for directory in (base / "res").glob("values-*"):
        match = re.match(r"values-([a-z]{2,3})(?:-|$)", directory.name)
        if match and match[1] not in allowed:
            shutil.rmtree(directory)
            removed.append(directory.name)
    sessions = base / "java/org/telegram/ui/SessionsActivity.java"
    change(sessions, "if (currentType == 0 && getMessagesController().qrLoginCamera)",
           f"if (!{JAVA_PREFIX}TgMaxRuntime.enabled() && currentType == 0 && getMessagesController().qrLoginCamera)")
    change(sessions, "return position == terminateAllSessionsRow || position >= otherSessionsStartRow",
           f"if ({JAVA_PREFIX}TgMaxRuntime.enabled() && currentType == 0) return position == terminateAllSessionsRow;\n            return position == terminateAllSessionsRow || position >= otherSessionsStartRow")
    login = base / "java/org/telegram/ui/LoginActivity.java"
    add_after_opening(login, r"public void saveSelfArgs\(Bundle outState\)",
                      f"        if ({JAVA_PREFIX}TgMaxRuntime.enabled()) {{ clearCurrentState(); return; }}\n")
    install_auth_translations(base / "res")
    return removed


def install_auth_translations(resources):
    mapping = {
        "language_title": ["Language"], "phone_title": ["YourPhone", "YourNumber"],
        "phone_description": ["StartText"], "phone_label": ["PhoneNumber"],
        "country_title": ["Country"], "country_search": ["SearchCountry"],
        "captcha_title": ["TgMaxCaptchaTitle"], "captcha_load_error": ["TgMaxCaptchaLoadError"],
        "captcha_webview_update": ["TgMaxCaptchaWebViewUpdate"], "captcha_rejected": ["TgMaxCaptchaRejected"],
        "action_continue": ["Next", "Continue"], "action_sign_in": ["StartMessaging"],
        "action_cancel": ["Cancel"], "action_back": ["Back"],
        "code_title": ["SentSmsCodeTitle"], "code_label": ["Code"],
        "code_description": ["SentSmsCode"], "action_resend": ["ResendCode"],
        "action_edit_phone": ["WrongNumber"], "error_invalid_phone": ["InvalidPhoneNumber"],
        "error_invalid_code": ["InvalidCode"], "error_expired_code": ["CodeExpired"],
        "error_unknown": ["ErrorOccurred"], "action_sign_out": ["LogOut"],
    }
    names = {"values": "English", "values-ru": "Русский", "values-es": "Español",
             "values-uz": "O‘zbek", "values-pt": "Português (Portugal)",
             "values-pt-rBR": "Português (Brasil)", "values-fr": "Français", "values-be": "Беларуская"}
    navigation = json.loads((ROOT / "integrations/telegram/navigation-strings.json").read_text())
    auth = json.loads((ROOT / "integrations/telegram/auth-strings.json").read_text())
    local = json.loads((ROOT / "integrations/telegram/local-strings.json").read_text())
    for qualifier, native_name in names.items():
        source = ROOT / "core/localization/src/main/res" / qualifier / "strings.xml"
        root = ET.parse(source).getroot()
        strings = {item.attrib["name"]: "".join(item.itertext()) for item in root.findall("string")}
        translations = {key: strings[name] for name, keys in mapping.items() for key in keys}
        translations.update(navigation[qualifier])
        translations.update(auth.get(qualifier, auth["values-pt-rBR"] if qualifier == "values-pt" else {}))
        translations.update(local[qualifier])
        translations.update(AppName="MAXGRAM CLIENT", AppNameBeta="MAXGRAM CLIENT", LanguageName=native_name)
        destination = resources / qualifier / "strings.xml"
        destination.parent.mkdir(parents=True, exist_ok=True)
        text = destination.read_text() if destination.exists() else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
        for key, value in translations.items():
            element = ET.Element("string", {"name": key})
            element.text = value
            replacement = ET.tostring(element, encoding="unicode", short_empty_elements=False)
            pattern = r'<string\s+name="' + key + r'"[^>]*>[\s\S]*?</string>'
            if re.search(pattern, text):
                text = re.sub(pattern, lambda m: replacement, text, count=1)
            else:
                text = text.replace("</resources>", "    " + replacement + "\n</resources>")
        ET.fromstring(text)
        destination.write_text(text)


def configure_build(target):
    release = json.loads((ROOT / "release.json").read_text())
    library = target / "TMessagesProj/build.gradle"
    app = target / "TMessagesProj_AppStandalone/build.gradle"
    for gradle in [library, app]:
        text = gradle.read_text().replace("apply plugin: 'com.google.gms.google-services'", "")
        text = text.replace("JavaVersion.VERSION_1_8", "JavaVersion.VERSION_17")
        text = text.replace("minSdkVersion 23", "minSdkVersion 27").replace("android-23", "android-27")
        text = text.replace("version '3.22.1'", "version '3.31.6'")
        gradle.write_text(text)
    change(library, "dependencies {", "dependencies {\n    implementation project(':tgmax-backend')\n    implementation 'com.github.luben:zstd-jni:1.5.7-7@aar'")
    change(library, "path 'jni/CMakeLists.txt'", "path 'jni/CMakeLists.txt'\n            version '3.31.6'")
    library.write_text(library.read_text() + "\nandroid.defaultConfig.ndk.abiFilters.add('arm64-v8a')\n")
    text = app.read_text().replace('applicationIdSuffix ".web"', '')
    text = text.replace('abiFilters "armeabi-v7a", "arm64-v8a", "x86", "x86_64"', 'abiFilters "arm64-v8a"')
    text = text.replace('output.versionCodeOverride = defaultConfig.versionCode * 10 + variant.productFlavors.get(0).abiVersionCode',
                        'output.versionCodeOverride = defaultConfig.versionCode')
    text = text.replace('localeFilters += ["zz"]', 'localeFilters += ["en", "ru", "es", "uz", "pt", "pt-rBR", "fr", "be", "zz"]')
    text += '\nandroid.signingConfigs.debug {\n    storeFile = file(System.getenv("TGMAX_ALPHA_KEYSTORE") ?: (System.getProperty("user.home") + "/.android/debug.keystore"))\n    storePassword = "android"\n    keyAlias = "androiddebugkey"\n    keyPassword = "android"\n}\n'
    app.write_text(text)
    settings = target / "settings.gradle"
    text = settings.read_text()
    for module in ['TMessagesProj_App', 'TMessagesProj_AppHuawei', 'TMessagesProj_AppHockeyApp', 'TMessagesProj_AppTests']:
        text = text.replace("include ':" + module + "'\n", '')
    settings.write_text(text + "\ninclude ':tgmax-backend'\n")
    shutil.copytree(ROOT / "max-backend", target / "tgmax-backend", ignore=shutil.ignore_patterns("build", ".gradle"))
    change(target / "tgmax-backend/build.gradle.kts",
           '    implementation("com.github.luben:zstd-jni:1.5.7-7")',
           '    compileOnly("com.github.luben:zstd-jni:1.5.7-7")\n    testImplementation("com.github.luben:zstd-jni:1.5.7-7")')
    rules = target / "TMessagesProj/proguard-rules.pro"
    rules.write_text(rules.read_text() + '\n-keep class com.github.luben.zstd.** { *; }\n')
    properties = target / "gradle.properties"
    text = properties.read_text()
    for key, value in {'APP_PACKAGE': 'io.github.lubara7.tgmax', 'APP_VERSION_NAME': release['version'], 'APP_VERSION_CODE': str(release['versionCode'])}.items():
        text = re.sub(r"(?m)^" + key + r"=.*$", key + '=' + value, text)
    properties.write_text(text)
    variables = target / "TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java"
    text = variables.read_text().replace('USE_CLOUD_STRINGS = true', 'USE_CLOUD_STRINGS = false').replace('CHECK_UPDATES = true', 'CHECK_UPDATES = false').replace('SUPPORTS_PASSKEYS = true', 'SUPPORTS_PASSKEYS = false')
    text = re.sub(r'APP_ID = \d+;', 'APP_ID = 0;', text)
    text = re.sub(r'APP_HASH = "[^"]*";', 'APP_HASH = "";', text)
    text = text.replace('LOGS_ENABLED = BuildConfig.DEBUG_VERSION', 'LOGS_ENABLED = false')
    text = text.replace('LOGS_ENABLED = DEBUG_VERSION || sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);', 'LOGS_ENABLED = false;')
    variables.write_text(text)
    for manifest in [target / 'TMessagesProj/src/main/AndroidManifest.xml', target / 'TMessagesProj/config/release/AndroidManifest_standalone.xml']:
        text = manifest.read_text()
        text = re.sub(r'android:allowBackup="[^"]*"', 'android:allowBackup="false"', text)
        if 'android:allowBackup=' not in text:
            text = text.replace('<application', '<application android:allowBackup="false"', 1)
        manifest.write_text(text)


def patch_password_login(target):
    path = target / "TMessagesProj/src/main/java/org/telegram/ui/LoginActivity.java"
    text = path.read_text()
    runtime = JAVA_PREFIX + "TgMaxRuntime"
    pattern = r'(if \((?:error\.text|err|errorText)[^\n]*contains\("SESSION_PASSWORD_NEEDED"\)\) \{)'
    def route(match):
        return match[0] + f'''
                if ({runtime}.get(currentAccount).needsPassword()) {{
                    showDoneButton(false, true);
                    Bundle maxParams = new Bundle();
                    maxParams.putBoolean("max_password", true);
                    setPage(VIEW_PASSWORD, true, maxParams, false);
                    return;
                }}'''
    text, count = re.subn(pattern, route, text)
    if count < 3:
        raise RuntimeError("Password routing anchors changed")
    start = text.index("public class LoginActivityPasswordView")
    end = text.index("public class ", start + 20)
    section = text[start:end]
    anchor = "            if (params.isEmpty()) {"
    assert section.count(anchor) == 1
    section = section.replace(anchor, f'''            if ({runtime}.enabled()) {{
                currentParams = params;
                nextPressed = false;
                codeField.setText("");
                codeField.setHint({runtime}.get(currentAccount).passwordHint());
                cancelButton.setVisibility(GONE);
                return;
            }}
{anchor}''')
    anchor = "            if (currentPassword == null) {"
    assert section.count(anchor) == 1
    section = section.replace(anchor, f'''            if ({runtime}.enabled()) {{
                String maxPassword = codeField.getText().toString();
                if (maxPassword.isEmpty()) {{ onPasscodeError(false); return; }}
                nextPressed = true;
                needShowProgress(0);
                {runtime}.get(currentAccount).checkPassword(maxPassword).whenComplete((auth, failure) ->
                    AndroidUtilities.runOnUIThread(() -> {{
                        nextPressed = false;
                        needHideProgress(false);
                        if (getParentActivity() == null || currentViewNum != VIEW_PASSWORD) return;
                        if (failure == null) {{
                            codeField.setText("");
                            AndroidUtilities.hideKeyboard(codeField);
                            onAuthSuccess(auth);
                        }} else {{
                            onPasscodeError(true);
                            needShowAlert(getString(R.string.ErrorOccurred), {runtime}.errorText(failure));
                        }}
                    }}));
                return;
            }}
{anchor}''')
    path.write_text(text[:start] + section + text[end:])


def isolate_client_services(target):
    java = target / "TMessagesProj/src/main/java"
    runtime = JAVA_PREFIX + "TgMaxRuntime"
    loader = java / "org/telegram/messenger/ApplicationLoader.java"
    add_after_opening(loader, r"private void initPushServices\(\)", f"        if ({runtime}.enabled()) return;\n")
    add_after_opening(loader, r"public static void startPushService\(\)", f"        if ({runtime}.enabled()) return;\n")
    launch = java / "org/telegram/ui/LaunchActivity.java"
    add_after_opening(launch, r"private BaseFragment getClientNotActivatedFragment\(\)", f"        if ({runtime}.enabled()) return new LoginActivity();\n")
    add_after_opening(java / "org/telegram/messenger/BillingController.java", r"public void startConnection\(\)",
                      f"        if ({runtime}.enabled()) return;\n")
    for relative in ['PremiumPreviewFragment.java', 'Stars/StarsIntroActivity.java', 'TON/TONIntroActivity.java', 'Wallet/WalletActivity.java']:
        path = java / 'org/telegram/ui' / relative
        if 'public boolean onFragmentCreate()' in path.read_text():
            add_after_opening(path, r'public boolean onFragmentCreate\(\)', f'        if ({runtime}.enabled()) return false;\n')
        else:
            add_after_opening(path, r'public class \w+ extends [^{]+', '\n    @Override public boolean onFragmentCreate() { return false; }\n')


def restrict_max_ui(target):
    java = target / "TMessagesProj/src/main/java/org/telegram"
    ui = java / "ui"
    phone = ui / "LoginActivity.java"
    runtime = JAVA_PREFIX + "TgMaxRuntime"
    add_after_opening(phone, r"public void fillNumber\(\)", f"        if ({runtime}.enabled()) return;\n")
    anchor = "            TelephonyManager tm = "
    src = phone.read_text()
    start = src.index("public void onNextPressed(String code)", src.index("public class PhoneView"))
    pos = src.index(anchor, start)
    src = src[:pos] + f"""            if ({runtime}.enabled() && activityMode == MODE_LOGIN) {{
                String digits = PhoneFormat.stripExceptNumbers(codeField.getText().toString())
                        + PhoneFormat.stripExceptNumbers(phoneField.getText().toString());
                if (codeField.length() == 0 || phoneField.length() == 0 || currentCountry == null || !digits.matches("[1-9][0-9]{{7,14}}")) {{
                    onFieldError(phoneOutlineView, false);
                    return;
                }}
                {runtime}.get(currentAccount).preparePhoneAuth(android.webkit.WebSettings.getDefaultUserAgent(getParentActivity()));
                Bundle params = new Bundle();
                params.putString("phoneFormated", digits);
                params.putString("phone", "+" + digits);
                params.putString("ephone", "+" + digits);
                if (currentCountry != null) params.putString("country", currentCountry.code);
                TLRPC.TL_auth_sendCode request = new TLRPC.TL_auth_sendCode();
                request.phone_number = "+" + digits;
                nextPressed = true;
                int token = ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) ->
                        AndroidUtilities.runOnUIThread(() -> {{
                            nextPressed = false;
                            needHideProgress(false);
                            if (getParentActivity() == null || currentViewNum != VIEW_PHONE_INPUT) return;
                            if (error != null && "MAX_CAPTCHA_CANCELLED".equals(error.text)) return;
                            if (error == null && response instanceof TLRPC.TL_auth_sentCode) {{
                                fillNextCodeParams(params, (TLRPC.auth_SentCode) response);
                            }} else {{
                                needShowAlert(getString(R.string.ErrorOccurred), error == null
                                        ? getString(R.string.ErrorOccurred) : maxAuthError(error.text));
                            }}
                        }}));
                needShowProgress(token);
                return;
            }}
""" + src[pos:]
    phone.write_text(src)
    text = phone.read_text()
    text = re.sub(r'proxyButtonView.setVisibility\([^;]+;', 'proxyButtonView.setVisibility(View.GONE);', text)
    phone.write_text(text)
    data = ui / "DataSettingsActivity.java"
    for row in ("proxySectionRow", "proxyRow", "proxySection2Row"):
        change(data, row + " = rowCount++;", row + " = -1;")
    dialogs = ui / "DialogsActivity.java"
    text = dialogs.read_text()
    text = re.sub(r'final boolean proxyVisible = [^;]+;', 'final boolean proxyVisible = false;', text)
    dialogs.write_text(text)
    add_after_opening(java / "messenger/AndroidUtilities.java", r"public static void showProxyAlert\(Activity activity, final ProxySettings settings\)", f"        if ({runtime}.enabled()) return;\n")
    add_after_opening(ui / "ProxyListActivity.java", r"public boolean onFragmentCreate\(\)", f"        if ({runtime}.enabled()) return false;\n")
    change(ui / "ProxySettingsActivity.java", "public class ProxySettingsActivity extends BaseFragment {", "public class ProxySettingsActivity extends BaseFragment {\n    @Override public boolean onFragmentCreate() { return false; }")
    settings = ui / 'SettingsActivity.java'
    text = settings.read_text()
    text = text.replace('if (walletAbove && getMessagesController().config.walletAvailable.get())', 'if (false)')
    a = text.index('        items.add(UItem.asShadow(null));\n        if (!walletAbove')
    b = text.index('        items.add(UItem.asHeader(getString(R.string.SettingsHelp)))', a)
    text = text[:a] + '\n        items.add(UItem.asShadow(null));\n        items.add(SettingCell.Factory.of(1100, 0xFF7189FF, 0xFF5267DB, R.drawable.settings_privacy, getString(R.string.TgMaxLocalSettings)));\n        items.add(SettingCell.Factory.of(15, 0xFFF45255, 0xFFDF3955, R.drawable.settings_business, getString(R.string.TelegramBusiness)));\n        items.add(UItem.asShadow(null));\n' + text[b:]
    text = text.replace('presentSettingFragment(new PremiumPreviewFragment(PremiumPreviewFragment.FEATURES_BUSINESS, "settings"));', 'Browser.openUrl(getParentActivity(), "https://business.max.ru/");')
    text = text.replace('            case 11:', f'            case 1100:\n                presentSettingFragment(new {JAVA_PREFIX}TgMaxSettingsActivity(currentAccount));\n                break;\n            case 11:')
    text = text.replace('showDialog(AlertsCreator.createSupportAlert(this, resourceProvider));', 'Browser.openUrl(getParentActivity(), "https://max.ru/support");')
    text = text.replace('LocaleController.getString(R.string.TelegramFaqUrl)', '"https://max.ru/support"')
    text = text.replace('Browser.openUrl(getParentActivity(), "https://telegram.org/privacy");', 'Browser.openUrl(getParentActivity(), "https://legal.max.ru/pp");')
    settings.write_text(text)


def configure_phone_login(target):
    ui = target / "TMessagesProj/src/main/java/org/telegram/ui"
    login = ui / "LoginActivity.java"
    rows = json.loads((ROOT / "integrations/telegram/login-countries.json").read_text())
    if len(rows) != 44 or len({item["iso"] for item in rows}) != 44:
        raise RuntimeError("Expected the 44 supported login countries")
    asset = target / "TMessagesProj/src/main/assets/countries.txt"
    formats = {}
    for line in asset.read_text().splitlines():
        fields = line.split(";")
        if len(fields) > 3: formats[fields[1]] = fields[3]
    asset.write_text("\n".join(";".join([item["code"], item["iso"], item["name"],
                     "XXX XXX XXXX" if item["code"] == "1" else formats.get(item["iso"], "")]) for item in rows) + "\n")
    change(login, "countriesArray.add(0, countryWithCode);", "countriesArray.add(countryWithCode);")
    change(login, "Collections.sort(countriesArray, Comparator.comparing(o -> o.name));", "")
    change(login, 'if (!TextUtils.equals(countryWithCode.code, "FT")) {',
           'if (!LocaleController.getLocaleStringIso639().startsWith("ru")) {')
    source = login.read_text()
    first = source.index("            String country = null;", source.index('open("countries.txt")'))
    last = source.index("            if (codeField.length() == 0) {\n                setCountryButtonText(null);", first)
    source = source[:first] + source[last:]
    source = source.replace("country = list.get(list.size() - 1);", "country = list.get(0);")
    phone_start = source.index("public class PhoneView")
    phone_end = source.index("public class LoginActivitySmsView", phone_start)
    phone = source[phone_start:phone_end]
    phone = phone.replace("            setGravity(Gravity.CENTER);",
                          "            setGravity(Gravity.CENTER);\n            setPadding(0, dp(32), 0, dp(152));", 1)
    # Neither Telegram contact sync nor its test datacenter exists in MAX. Remove
    # their rows rather than leaving hidden controls in the layout calculation.
    first = phone.index("            if (newAccount && activityMode == MODE_LOGIN) {", phone.index("            int bottomMargin = 72;"))
    last = phone.index("            if (bottomMargin > 0 && !AndroidUtilities.isSmallScreen()) {", first)
    phone = phone[:first] + "            syncContacts = false;\n\n" + phone[last:]
    first = phone.index("            titleView.setOnClickListener(v -> {")
    last = phone.index("            subtitleView = ", first)
    phone = phone[:first] + phone[last:]
    source = source[:phone_start] + phone + source[phone_end:]
    source = source.replace("private boolean syncContacts = true;", "private boolean syncContacts = false;")
    source = source.replace('syncContacts = savedInstanceState.getInt("syncContacts", 1) == 1;', 'syncContacts = false;')
    login.write_text(source)
    runtime = JAVA_PREFIX + "TgMaxRuntime"
    add_after_opening(login, r"private void loadCountries\(\)", f"        if ({runtime}.enabled()) return;\n")
    selector = ui / "CountrySelectActivity.java"
    add_after_opening(selector, r"public CountryAdapter\(Context context, ArrayList<Country> exisitingCountries, boolean disableAnonymousNumbers\)", f"""            if ({runtime}.enabled()) {{
                mContext = context;
                ArrayList<Country> entries = new ArrayList<>();
                if (exisitingCountries != null) entries.addAll(exisitingCountries);
                else {{
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            ApplicationLoader.applicationContext.getAssets().open("countries.txt"), java.nio.charset.StandardCharsets.UTF_8))) {{
                        String line;
                        while ((line = reader.readLine()) != null) {{
                            String[] columns = line.split(";");
                            Country country = new Country();
                            country.code = columns[0]; country.shortname = columns[1]; country.name = columns[2];
                            if (!LocaleController.getLocaleStringIso639().startsWith("ru"))
                                country.name = LocaleController.getCountryName(country.shortname);
                            entries.add(country);
                        }}
                    }} catch (Exception error) {{ throw new IllegalStateException("Missing MAX countries", error); }}
                }}
                countries.put("", entries);
                sortedCountries.add("");
                return;
            }}
""")
    hooks = (ROOT / "integrations/telegram/login-hooks.java.txt").read_text()
    add_after_opening(login, r"public class LoginActivity extends BaseFragment[^{{]*", hooks)
    add_after_opening(login, r"public View createView\(Context context\)", f"""        {runtime}.get(currentAccount).setCaptchaHandler(maxCaptchaHandler);
""")
    change(login, '        floatingButton.addAdditionalView(floatingButtonIcon);',
           '        floatingButton.addAdditionalView(floatingButtonIcon);\n        setupMaxLoginControls(context);')
    change(login, 'floatingButton.setContentDescription(getString(R.string.Done));',
           'floatingButton.setContentDescription(getString(R.string.Continue));')
    change(login, '                super.onMeasure(widthMeasureSpec, heightMeasureSpec);\n            }\n        };\n        sizeNotifierFrameLayout.setDelegate',
           '                layoutMaxLoginControls(keyboardOffset, statusBarHeight);\n                super.onMeasure(widthMeasureSpec, heightMeasureSpec);\n            }\n        };\n        sizeNotifierFrameLayout.setDelegate')
    change(login, '        currentViewNum = page;', '        currentViewNum = page;\n        updateMaxLoginControls();', 2)
    add_after_opening(login, r"private void fillNextCodeParams\(Bundle params, TLRPC.auth_SentCode res, boolean animate\)",
        f"""        if (res instanceof {runtime}.CallSentCode) {{
            showDoneButton(false, true);
            AndroidUtilities.hideKeyboard(fragmentView);
            presentFragment(new {JAVA_PREFIX}TgMaxCallActivity(currentAccount, params.getString("ephone", ""),
                    (({runtime}.CallSentCode) res).call, (result, failure) -> handleMaxVerification(params, result, failure)));
            return;
        }}
""")
    add_after_opening(login, r"public void onFragmentDestroy\(\)", f"""        {runtime}.get(currentAccount).clearCaptchaHandler(maxCaptchaHandler);
        if (maxCaptchaDialog != null) maxCaptchaDialog.dismiss();
""")


def remove_native_transport(target):
    jni = target / "TMessagesProj/jni"
    cmake = jni / "CMakeLists.txt"
    source = cmake.read_text()
    start = source.index("add_library(tgnet STATIC")
    end = source.index("target_compile_options(tgnet", start)
    source = source[:start] + """add_library(tgmax_buffers STATIC
        openssl/aes_ige.c
        tgnet/BuffersStorage.cpp
        tgnet/ByteArray.cpp
        tgnet/FileLog.cpp
        tgnet/NativeByteBuffer.cpp)
""" + source[end:]
    source = re.sub(r"\btgnet(?= PUBLIC| PROPERTIES|\n)", "tgmax_buffers", source)
    cmake.write_text(source.replace("libtgnet.a", "libtgmax_buffers.a"))
    change(cmake, "        tests.cpp\n", "")

    bridge = jni / "TgNetWrapper.cpp"
    original = bridge.read_text()
    buffers = original[original.index("jlong getFreeBuffer("):original.index("jlong getCurrentTimeMillis(")]
    bridge.write_text("""#include <jni.h>
#include "tgnet/BuffersStorage.h"
#include "tgnet/NativeByteBuffer.h"

JavaVM *javaVm = nullptr;
jclass jclass_ByteBuffer = nullptr;
jmethodID jclass_ByteBuffer_allocateDirect = nullptr;

""" + buffers + """
extern "C" int registerNativeBuffers(JavaVM *vm, JNIEnv *env) {
    javaVm = vm;
    jclass clazz = env->FindClass(NativeByteBufferClassPathName);
    if (clazz == nullptr) return JNI_FALSE;
    jint result = env->RegisterNatives(clazz, NativeByteBufferMethods,
            sizeof(NativeByteBufferMethods) / sizeof(NativeByteBufferMethods[0]));
    env->DeleteLocalRef(clazz);
    return result == JNI_OK ? JNI_TRUE : JNI_FALSE;
}
""")
    change(jni / "jni.c", "registerNativeTgNetFunctions", "registerNativeBuffers", 2)
    manager = target / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"
    change(manager, "public static native void native_setJava(boolean useJavaByteBuffers);",
           "public static void native_setJava(boolean useJavaByteBuffers) {}")
    change(manager, "public static native boolean native_isGoodPrime(byte[] prime, int g);",
           "public static boolean native_isGoodPrime(byte[] prime, int g) { return false; }")
    manager.write_text(re.sub(r"(?m)^\s*public static native [^;\n]+;", "", manager.read_text()))


def verify_guards(target, native_names):
    root = target / "TMessagesProj/src"
    for path in root.rglob("*"):
        if path.suffix not in (".java", ".kt"):
            continue
        text = path.read_text()
        for name in native_names:
            if path.name == "ConnectionsManager.java":
                text_without_declarations = re.sub(r"public static native [^;]+;", "", text)
                if re.search(r"\b" + name + r"\s*\(", text_without_declarations):
                    raise RuntimeError(f"Unprotected native call: {name}")
            elif re.search(r"\b" + name + r"\s*\(", text):
                raise RuntimeError(f"Unprotected native call in {path}")


def install_max_assets(target):
    import base64
    import gzip
    resources = target / "TMessagesProj/src/main/res"
    source = ROOT / "integrations/telegram/resources"
    shutil.copytree(source, resources, dirs_exist_ok=True)
    for path in resources.rglob("*.b64"):
        path.with_suffix("").write_bytes(base64.b64decode(path.read_text()))
        path.unlink()
    for path in resources.rglob("*.json.gz"):
        path.with_suffix("").write_bytes(gzip.decompress(path.read_bytes()))
        path.unlink()
    for manifest in [target / "TMessagesProj/src/main/AndroidManifest.xml", target / "TMessagesProj/config/release/AndroidManifest_standalone.xml"]:
        text = re.sub(r'android:(icon|roundIcon)="@mipmap/[^"]+"',
                      lambda m: 'android:' + m[1] + '="@mipmap/maxgram_launcher"', manifest.read_text())
        manifest.write_text(text)
    for style in resources.rglob("styles.xml"):
        text = style.read_text()
        text = re.sub(r'(<item name="android:windowSplashScreenAnimatedIcon">)[^<]+(</item>)',
                      r'\g<1>@mipmap/maxgram_launcher\2', text)
        style.write_text(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "upstream/telegram")
    parser.add_argument("--output", type=Path, default=ROOT / "build/telegram-client")
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    lock = json.loads((ROOT / "upstream.lock.json").read_text())["sources"]["telegram"]
    if not (source / ".git").exists():
        raise SystemExit("Initialize upstream/telegram first: git submodule update --init --depth 1 upstream/telegram")
    if run("git", "rev-parse", "HEAD", cwd=source) != lock["commit"]:
        raise SystemExit("Telegram checkout does not match upstream.lock.json")
    if output.exists():
        raise SystemExit("Output already exists. Choose a new --output; existing edits were preserved.")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "clone", "--no-hardlinks", "--no-checkout", str(source), str(output)], check=True)
    subprocess.run(["git", "checkout", "--detach", lock["commit"]], cwd=output, check=True)
    native_names = guard_mtproto(output)
    remove_native_transport(output)
    removed = restrict_languages(output)
    configure_build(output)
    patch_password_login(output)
    isolate_client_services(output)
    restrict_max_ui(output)
    configure_phone_login(output)
    shutil.copytree(ROOT / "integrations/telegram/src/main/java", output / "TMessagesProj/src/main/java", dirs_exist_ok=True)
    shutil.copytree(ROOT / "integrations/telegram/src/main/assets", output / "TMessagesProj/src/main/assets", dirs_exist_ok=True)
    for helper in (ROOT / "integrations/telegram/ui").glob("*.java"):
        shutil.copy2(helper, output / "TMessagesProj/src/main/java/io/github/lubara7/tgmax/telegram" / helper.name)
    image_location = output / "TMessagesProj/src/main/java/org/telegram/messenger/ImageLocation.java"
    add_after_opening(image_location, r"public static ImageLocation getForUser\(int currentAccount, TLRPC.User user, int type\)", f"""        if (user != null && type != TYPE_STRIPPED && type != TYPE_VIDEO_BIG && type != TYPE_VIDEO_SMALL) {{
            String maxUrl = {JAVA_PREFIX}TgMaxRuntime.get(currentAccount).avatarUrl(user.id);
            if (!maxUrl.isEmpty()) return ImageLocation.getForPath(maxUrl);
        }}
""")
    add_after_opening(image_location, r"public static ImageLocation getForChat\(int currentAccount, TLRPC.Chat chat, int type\)", f"""        if (chat != null && type != TYPE_STRIPPED && type != TYPE_VIDEO_BIG && type != TYPE_VIDEO_SMALL) {{
            String maxUrl = {JAVA_PREFIX}TgMaxRuntime.get(currentAccount).chatAvatarUrl(chat.id);
            if (!maxUrl.isEmpty()) return ImageLocation.getForPath(maxUrl);
        }}
""")
    install_max_assets(output)
    verify_guards(output, native_names)
    report = {"telegram_commit": lock["commit"], "network_entrypoints_guarded": len(native_names),
              "removed_locale_directories": removed, "native_apk_verified": False}
    (output / "TGMAX_CHANGES.json").write_text(json.dumps(report, indent=2) + "\n")
    (output / "TGMAX_CHANGES.md").write_text("MAXGRAM CLIENT modifies this Telegram checkout to use the Komet-derived MAX backend.\nSee the parent project's SOURCE_PROVENANCE.md and docs/telegram-migration.md.\nUpstream copyright and GPL notices are retained.\n")
    print(json.dumps({"client_directory": str(output), **report}, indent=2))


if __name__ == "__main__":
    main()
