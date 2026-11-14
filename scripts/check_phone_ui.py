import re
import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def adb(*args):
    return subprocess.run(['adb', *args], check=True, capture_output=True).stdout


def snapshot(filename):
    adb('shell', 'uiautomator', 'dump', '/sdcard/tgmax-ui.xml')
    adb('pull', '/sdcard/tgmax-ui.xml', filename)
    return list(ET.parse(filename).iter('node'))


def wait_for(filename, predicate):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        try:
            nodes = snapshot(filename)
            if predicate(nodes):
                return nodes
        except (subprocess.CalledProcessError, ET.ParseError, OSError):
            pass
        time.sleep(2)
    raise RuntimeError('Expected Android login UI did not appear')


def tap(node):
    left, top, right, bottom = map(int, re.findall(r'\d+', node['bounds']))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))


def assert_login_layout(nodes, strings, qualifier):
    labels = json.loads(Path('integrations/telegram/auth-strings.json').read_text())[qualifier]
    policy = next(n for n in nodes if all(labels[k] in n.get('text', '') for k in ['TgMaxPrivacy', 'TgMaxTerms', 'TgMaxRecommendations']))
    phone = next(n for n in nodes if n.get('content-desc') == strings['phone_label'])
    done = next(n for n in nodes if n.get('content-desc') == strings['action_continue'])
    def overlaps(first, second):
        a, b, c, d = map(int, re.findall(r'\d+', first.get('bounds')))
        x, y, z, w = map(int, re.findall(r'\d+', second.get('bounds')))
        return min(c, z) > max(a, x) and min(d, w) > max(b, y)
    assert not overlaps(policy, phone), 'Legal text overlaps the phone input'
    assert not overlaps(policy, done), 'Legal text overlaps Continue'
    phone_bottom = list(map(int, re.findall(r'\d+', phone.get('bounds'))))[3]
    policy_top = list(map(int, re.findall(r'\d+', policy.get('bounds'))))[1]
    assert policy_top - phone_bottom >= 8, 'No space between the phone input and legal text'
    assert not any(n.get('checkable') == 'true' for n in nodes), 'Telegram-only login checkbox remains visible'
    title = next(n for n in nodes if n.get('text') == strings['phone_title'])
    help_button = next(n for n in nodes if n.get('content-desc') == labels['TgMaxHelp'])
    title_top = list(map(int, re.findall(r'\d+', title.get('bounds'))))[1]
    help_bottom = list(map(int, re.findall(r'\d+', help_button.get('bounds'))))[3]
    assert title_top - help_bottom >= 8, 'Phone title overlaps the login toolbar'


try:
    nodes = wait_for('login.xml', lambda rows: any(n.get('content-desc') == 'Phone number' for n in rows))
    assert any(n.get('text') == 'Check the country code and enter the phone number from your MAX account.' for n in nodes)
    Path('login.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    assert any(n.get('content-desc') == 'Help' for n in nodes)
    assert any(n.get('content-desc') == 'Log in by QR code' for n in nodes)
    assert any('privacy policy' in n.get('text', '') and 'user agreement' in n.get('text', '') for n in nodes)
    default_strings = {n.get('name'): n.text for n in ET.parse(Path('core/localization/src/main/res/values/strings.xml')).findall('string')}
    assert_login_layout(nodes, default_strings, 'values')
    country = next(n for n in nodes if n.get('content-desc') == 'Country')
    tap(country.attrib)
    expected = ['Russia', 'Belarus', 'Azerbaijan', 'Armenia']
    nodes = wait_for('countries.xml', lambda rows: all(any(name in n.get('text', '') for n in rows) for name in expected))
    positions = [next(i for i, n in enumerate(nodes) if name in n.get('text', '')) for name in expected]
    assert positions == sorted(positions)
    Path('countries.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap(nodes[positions[0]].attrib)
    nodes = wait_for('login-country.xml', lambda rows: any(n.get('content-desc') == 'Country code' and n.get('text') == '7' for n in rows))
    assert any('Russia' in n.get('text', '') for n in nodes)
    languages = ['Русский', 'English', 'Español', 'Português (Brasil)', 'O‘zbek', 'Français', 'Беларуская']
    qualifiers = ['values-ru', 'values', 'values-es', 'values-pt-rBR', 'values-uz', 'values-fr', 'values-be']
    language_label = 'Language'
    for index, (native, qualifier) in enumerate(zip(languages, qualifiers)):
        nodes = snapshot('before-language.xml')
        tap(next(n.attrib for n in nodes if n.get('content-desc') == language_label))
        nodes = wait_for('languages.xml', lambda rows: all(any(n.get('text') == name for n in rows) for name in languages))
        positions = [next(i for i, n in enumerate(nodes) if n.get('text') == name) for name in languages]
        assert positions == sorted(positions)
        if index == 0: Path('languages.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        tap(next(n.attrib for n in nodes if n.get('text') == native))
        strings = {n.get('name'): n.text for n in ET.parse(Path('core/localization/src/main/res') / qualifier / 'strings.xml').findall('string')}
        nodes = wait_for('localized-login.xml', lambda rows: any(n.get('text') == strings['phone_title'] for n in rows))
        assert any(n.get('text') == strings['phone_description'] for n in nodes)
        assert any(n.get('content-desc') == strings['country_title'] for n in nodes)
        assert any(n.get('text') == '7' for n in nodes)
        assert_login_layout(nodes, strings, qualifier)
        Path(f'login-{qualifier}.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        Path(f'login-{qualifier}.xml').write_bytes(Path('localized-login.xml').read_bytes())
        language_label = strings['language_title']
    print('Published MAXGRAM APK: phone copy, ordered countries, help, legal footer and seven language switches checked')
except Exception:
    for name, args in [('startup-logcat.txt', ('logcat', '-d')), ('activity-state.txt', ('shell', 'dumpsys', 'activity', 'activities'))]:
        try:
            Path(name).write_bytes(adb(*args))
        except subprocess.CalledProcessError:
            pass
    try:
        Path('failure.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        adb('root')
        adb('wait-for-device')
        adb('pull', '/data/anr', 'anr-traces')
    except subprocess.CalledProcessError:
        pass
    raise
