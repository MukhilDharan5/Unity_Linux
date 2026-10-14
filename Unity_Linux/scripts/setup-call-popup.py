#!/usr/bin/env python3
"""Keep Unity's call controls visible above other windows on KDE Plasma."""
import shutil
import subprocess


def main():
    if not shutil.which('kwriteconfig6') or not shutil.which('kreadconfig6'):
        raise SystemExit('This call popup visibility setting requires KDE Plasma')
    read = lambda key: subprocess.check_output(['kreadconfig6','--file','kwinrulesrc','--group','General','--key',key],text=True).strip()
    rules = [rule for rule in read('rules').split(',') if rule]
    # Preserve older numeric rule groups as well as modern named rules.
    if not rules:
        count = read('count')
        rules = [str(index) for index in range(1, int(count or '0') + 1)]
    identity = 'unity-call-controls'
    values = {'Description':'Unity call controls', 'wmclass':'org.unityconnect.Unity', 'wmclassmatch':'1',
        'wmclasscomplete':'false', 'title':'Unity · Call controls', 'titlematch':'1', 'above':'true', 'aboverule':'2'}
    for key,value in values.items():
        subprocess.run(['kwriteconfig6','--file','kwinrulesrc','--group',identity,'--key',key,value],check=True)
    if identity not in rules: rules.append(identity)
    subprocess.run(['kwriteconfig6','--file','kwinrulesrc','--group','General','--key','rules',','.join(rules)],check=True)
    subprocess.run(['busctl','--user','call','org.kde.KWin','/KWin','org.kde.KWin','reconfigure'],check=True)
    print('Unity call controls will stay above other windows.')


if __name__ == '__main__': main()
