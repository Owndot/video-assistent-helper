# S4b-5: erzeugt die neutralen MPD-Fixtures der Golden-Vektoren dash-gate/1 (dieselben Dateien liegen byte-gleich im Helfer
# unter tests/java/dash/fixtures/gate). Die ERWARTUNGEN stehen von Hand in gate-v1-vectors.tsv (mit Rechenweg) – sie werden
# weder von Kadri noch vom Helfer erzeugt. Ein neues Fixture: hier ergänzen, Erwartung von Hand eintragen, SHA-256 eintragen.
#   python3 erzeuge_fixtures.py ZIELORDNER
import os, sys
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
NS = 'urn:mpeg:dash:schema:mpd:2011'
def mpd(body, dur='PT20S', typ='static', extra=''):
    d = ' mediaPresentationDuration="%s"' % dur if dur else ''
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<MPD xmlns="%s" type="%s"%s profiles="urn:mpeg:dash:profile:isoff-live:2011" minBufferTime="PT2S">\n%s'
            '%s</MPD>\n') % (NS, typ, d, extra, body)
def period(sets, attrs=''): return '  <Period id="0"%s>\n%s  </Period>\n' % (attrs, sets)
def tl(entries): return '<SegmentTimeline>' + ''.join('<S %s/>' % e for e in entries) + '</SegmentTimeline>'
V_TL = tl(['t="0" d="51200" r="4"'])            # 5 × 4 s bei 12800
A_TL = tl(['t="0" d="192000" r="4"'])           # 5 × 4 s bei 48000
def vtpl(timeline=V_TL, media='v/$RepresentationID$/$Number%05d$.m4s', init='v/$RepresentationID$/init.mp4', extra='', ts=12800):
    i = ' initialization="%s"' % init if init else ''
    return '<SegmentTemplate timescale="%d"%s media="%s" startNumber="1"%s>%s</SegmentTemplate>' % (ts, i, media, extra, timeline)
def atpl(timeline=A_TL, media='a/$RepresentationID$/$Number%05d$.m4s', init='a/$RepresentationID$/init.mp4', extra='', ts=48000):
    return '<SegmentTemplate timescale="%d" initialization="%s" media="%s" startNumber="1"%s>%s</SegmentTemplate>' % (ts, init, media, extra, timeline)
def vrep(i, codecs, bw, w, h, inner=''): return '      <Representation id="%s" mimeType="video/mp4" codecs="%s" bandwidth="%d" width="%d" height="%d">%s</Representation>\n' % (i, codecs, bw, w, h, inner)
def arep(i, codecs='mp4a.40.2', bw=48000, inner='', mime='audio/mp4'): return '      <Representation id="%s" mimeType="%s" codecs="%s" bandwidth="%d" audioSamplingRate="48000">%s</Representation>\n' % (i, mime, codecs, bw, inner)
def vset(tpl=None, reps=None, extra=''):
    reps = reps if reps is not None else vrep('v0', 'avc1.4d400c', 90000, 320, 180) + vrep('v1', 'avc1.4d401e', 320000, 640, 360)
    return '    <AdaptationSet id="0" contentType="video">\n%s      %s\n%s    </AdaptationSet>\n' % (extra, tpl if tpl is not None else vtpl(), reps)
def aset(tpl=None, reps=None, sid='1', lang='de', extra=''):
    reps = reps if reps is not None else arep('a0')
    return '    <AdaptationSet id="%s" contentType="audio" lang="%s">\n%s      %s\n%s    </AdaptationSet>\n' % (sid, lang, extra, tpl if tpl is not None else atpl(), reps)
def slist(n, ts, d=None, timeline='', prefix='x'):
    du = ' duration="%d"' % d if d else ''
    return '<SegmentList timescale="%d"%s><Initialization sourceURL="%s/init.mp4"/>%s%s</SegmentList>' % (ts, du, prefix, timeline,
        ''.join('<SegmentURL media="%s/seg-%05d.m4s"/>' % (prefix, k) for k in range(1, n + 1)))
def vlist_reps(n, d=None, timeline=''):   # SegmentList je Darstellung (eigene Adressen)
    return vrep('v0', 'avc1.4d400c', 90000, 320, 180, slist(n, 12800, d, timeline, 'v/v0')) + vrep('v1', 'avc1.4d401e', 320000, 640, 360, slist(n, 12800, d, timeline, 'v/v1'))
F = {}
B = vset() + aset()
# ---------- angeboten ----------
F['g01-number-timeline'] = mpd(period(B))
F['g02-number-duration'] = mpd(period(vset(vtpl(timeline='', extra=' duration="51200"')) + aset(atpl(timeline='', extra=' duration="192000"'))))
F['g03-time-timeline'] = mpd(period(vset(vtpl(media='v/$RepresentationID$/$Time$.m4s')) + aset(atpl(media='a/$RepresentationID$/$Time$.m4s'))))
F['g04-list-timeline'] = mpd(period(vset('', vlist_reps(5, None, V_TL)) + aset('', arep('a0', inner=slist(5, 48000, None, A_TL, 'a/a0')))))
F['g05-list-duration'] = mpd(period(vset('', vlist_reps(5, 51200)) + aset('', arep('a0', inner=slist(5, 48000, 192000, '', 'a/a0')))))
F['g06-packager-tail'] = mpd(period(vset() + aset(atpl(timeline=tl(['t="0" d="192000" r="4"', 'd="2544"'])))), dur='PT20.053S')
F['g07-audio-beyond-end'] = mpd(period(vset() + aset(atpl(timeline=tl(['t="0" d="192000" r="5"'])))))
F['g08-lc-and-he-aac'] = mpd(period(vset() + aset() + aset(sid='2', lang='en', reps=arep('a1', 'mp4a.40.5'))))
F['g09-baseurl-chain'] = mpd(period(B, '').replace('<Period id="0">', '<Period id="0">\n    <BaseURL>p/</BaseURL>'), extra='  <BaseURL>https://cdn.example/root/</BaseURL>\n')
F['g10-avc-and-hevc'] = mpd(period(vset(reps=vrep('v0', 'avc1.4d400c', 90000, 320, 180) + vrep('v1', 'hvc1.1.6.L93.B0', 320000, 640, 360)) + aset()))
# ---------- abgelehnt ----------
F['n01-list-audio-24s'] = mpd(period(vset('', vlist_reps(5, 51200)) + aset('', arep('a0', inner=slist(6, 48000, 192000, '', 'a/a0')))))
F['n02-timeline-gap'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="51200" r="1"', 't="128000" d="51200" r="2"']))) + aset()))
F['n03-timeline-overlap'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="51200" r="1"', 't="89600" d="51200" r="2"']))) + aset()))
F['n04-video-12s'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="51200" r="2"']))) + aset()))
F['n05-audio-16s'] = mpd(period(vset() + aset(atpl(timeline=tl(['t="0" d="192000" r="3"'])))))
F['n06-init-missing'] = mpd(period(vset(vtpl(init=None)) + aset()))
F['n07-segmentbase'] = mpd(period(vset('', vrep('v0', 'avc1.4d400c', 90000, 320, 180, '<BaseURL>v0.mp4</BaseURL><SegmentBase indexRange="800-900"><Initialization range="0-799"/></SegmentBase>')
                                      + vrep('v1', 'avc1.4d401e', 320000, 640, 360, '<BaseURL>v1.mp4</BaseURL><SegmentBase indexRange="800-900"><Initialization range="0-799"/></SegmentBase>')) + aset()))
F['n08-time-without-timeline'] = mpd(period(vset(vtpl(timeline='', media='v/$RepresentationID$/$Time$.m4s', extra=' duration="51200"')) + aset()))
F['n09-hevc'] = mpd(period(vset(reps=vrep('v0', 'hvc1.1.6.L93.B0', 90000, 320, 180) + vrep('v1', 'hvc1.1.6.L93.B0', 320000, 640, 360)) + aset()))
F['n10-opus-in-mp4'] = mpd(period(vset() + aset(reps=arep('a0', 'opus'))))
F['n11-muxed'] = mpd(period(vset(reps=vrep('v0', 'avc1.4d400c,mp4a.40.2', 90000, 320, 180) + vrep('v1', 'avc1.4d401e,mp4a.40.2', 320000, 640, 360)) + aset()))
F['n12-he-aac-only'] = mpd(period(vset() + aset(reps=arep('a0', 'mp4a.40.5'))))
F['n13-no-audio'] = mpd(period(vset()))
F['n14-dynamic'] = mpd(period(B), typ='dynamic')
F['n15-multiperiod'] = mpd(period(B, ' duration="PT10S"') + period(B, ' duration="PT10S"').replace('<Period id="0"', '<Period id="1"'))
F['n16-drm'] = mpd(period(vset(extra='      <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>\n') + aset()))
F['n17-duration-and-timeline'] = mpd(period(vset(vtpl(extra=' duration="51200"')) + aset()))
F['n18-addressing-mixed'] = mpd(period(vset(slist(5, 12800, 51200, '', 'v/shared'), vrep('v0', 'avc1.4d400c', 90000, 320, 180, vtpl())) + aset()))
F['n19-baseurl-multiple'] = mpd(period(B), extra='  <BaseURL>https://cdn-a.example/v/</BaseURL>\n  <BaseURL>https://cdn-b.example/v/</BaseURL>\n')
F['n20-segments-per-track'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="128" r="20000"']))) + aset(atpl(timeline=tl(['t="0" d="192000" r="49"'])))), dur='PT200S')
F['n21-expansion-limit'] = mpd(period(vset('', ''.join(vrep('v%d' % i, 'avc1.4d400c', 90000, 320, 180,
        vtpl(timeline=tl(['t="0" d="1" r="19999"']), ts=1)) for i in range(51)))
    + aset(atpl(timeline=tl(['t="0" d="1" r="19999"']), ts=1))), dur='PT20000S')
F['n22-zero-duration'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="0" r="4"']))) + aset()))
F['n23-timeline-repeat'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="51200" r="-1"', 't="100000" d="51200" r="1"']))) + aset()))
F['n24-segment-over-1h'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="92160000"']))) + aset(atpl(timeline=tl(['t="0" d="192000" r="1799"'])))), dur='PT7200S')
F['n25-track-collision'] = mpd(period(vset(vtpl(media='v/seg-$Number%05d$.m4s')) + aset()))
F['n26-options-limit'] = mpd(period(vset(reps=''.join(vrep('v%d' % i, 'avc1.4d400c', 90000 + i, 320, 180) for i in range(17)))
    + aset(reps=''.join(arep('a%d' % i, bw=48000 + i) for i in range(16)))))
F['n27-size-limit'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="51200" r="49"'])), reps=vrep('v0', 'avc1.4d400c', 1000000000, 320, 180) + vrep('v1', 'avc1.4d401e', 1000000000, 640, 360))
    + aset(atpl(timeline=tl(['t="0" d="192000" r="49"'])))), dur='PT200S')
F['n28-journal-limit'] = mpd(period(vset(vtpl(timeline=tl(['t="0" d="1" r="19999"']), ts=1), reps=vrep('v0', 'avc1.4d400c', 90000, 320, 180))
    + aset(atpl(timeline=tl(['t="0" d="1" r="19999"']), ts=1))), dur='PT20000S')
F['n29-duration-unknown'] = mpd(period(B), dur=None)
F['n30-type-unknown'] = mpd(period(B), typ='vod')
F['n31-essential-unknown'] = mpd(period(vset(extra='      <EssentialProperty schemeIdUri="urn:example:unbekannt" value="1"/>\n') + aset()))
os.makedirs(OUT, exist_ok=True)
for k, v in F.items():
    with open(os.path.join(OUT, k + '.mpd'), 'w', encoding='utf-8', newline='\n') as f: f.write(v)
print(len(F), 'Fixtures nach', OUT)
