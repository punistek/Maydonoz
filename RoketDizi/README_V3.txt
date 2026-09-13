RoketDizi CloudStream Runtime V3

V2'deki sabit merkez tıklaması kaldırıldı.

V3:
- RoketDizi detail sayfasını WebView'da doğal şekilde açar.
- Pichive iframe isteğini yakalar ve player'ı top-level açar.
- Pichive DOM'unda #Player, .alertCenter, video ve gerçek play/player yüzeylerini tarar.
- getBoundingClientRect ile gerçek CSS koordinatını alır.
- CSS koordinatını WebView view koordinatına dönüştürür.
- JS element.click() / player API kullanmadan Android MotionEvent ile gerçek dokunuş gönderir.
- .m3u8 isteğini network gözleminden yakalar.
- Referer + User-Agent + Cookie ile CloudStream ExtractorLink üretir.

Beklenen log:
RUNTIME DOM_CANDIDATE ...
RUNTIME DOM_TAP ...
RUNTIME HLS ...
RUNTIME MEDIA ...
EMIT runtime HLS
