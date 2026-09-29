# PARS SkyStream - DiziPod

SkyStream > Settings > Extensions > Add Source bölümüne GitHub'a yükledikten sonra repo.json RAW adresini ekleyin.

Dosyalar:
- repo.json
- plugins.json
- dizipod/plugin.json
- dizipod/plugin.js

Test komutları (SkyStream CLI):
- skystream test -f getHome
- skystream test -f search -q "Ted Lasso"
- skystream test -f load -q "https://dizipod.com/diziler/..."
- skystream test -f loadStreams -q "https://dizipod.com/film/..."
