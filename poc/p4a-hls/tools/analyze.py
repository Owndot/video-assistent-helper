#!/usr/bin/env python3
# P4a: checks one remuxed MP4 against the HLS source it came from (needs ffmpeg/ffprobe on the host).
# Content-based: every 2 s the source shows a white frame and starts a 1 kHz beep at the same instant (make-fixtures.sh).
#   - exactly one H.264 video and one audio stream of the expected codec (no silent video-only output)
#   - durations and video frame count equal to the source (+-0.15 s / exact frames)
#   - full decode without errors
#   - every flash present; beep-minus-flash offset of the output equals the source's within 30 ms (A/V sync)
#   python3 -I analyze.py SOURCE.m3u8 OUTPUT.mp4 [--audio aac|opus] [--json result.json]
import json, re, subprocess, sys

def run(args):
    return subprocess.run(args, capture_output=True, text=True)

def probe(path, hls):
    a = ['ffprobe', '-v', 'error'] + (['-allowed_extensions', 'ALL'] if hls else []) + [
        '-count_frames', '-show_entries', 'format=duration:stream=codec_type,codec_name,profile,nb_read_frames,duration,start_time',
        '-of', 'json', path]
    j = json.loads(run(a).stdout or '{}')
    seen, streams = set(), []
    for s in j.get('streams', []):   # the HLS demuxer may list a stream once per program
        key = (s.get('codec_type'), s.get('codec_name'), s.get('start_time'))
        if key not in seen:
            seen.add(key); streams.append(s)
    return float(j.get('format', {}).get('duration') or 0), streams

def flashes(path, hls):
    a = ['ffmpeg', '-hide_banner', '-nostdin', '-copyts'] + (['-allowed_extensions', 'ALL'] if hls else []) + [
        '-i', path, '-map', '0:v:0', '-vf', 'scale=8:8,format=gray,showinfo', '-f', 'null', '-']
    out = []
    for m in re.finditer(r'pts_time:\s*([-0-9.]+).*?mean:\[(\d+)', run(a).stderr):
        if int(m.group(2)) > 200: out.append(float(m.group(1)))
    return out

def beeps(path, hls):
    a = ['ffmpeg', '-hide_banner', '-nostdin', '-copyts'] + (['-allowed_extensions', 'ALL'] if hls else []) + [
        '-i', path, '-map', '0:a:0', '-af',
        'aresample=48000,asetnsamples=n=240:p=0,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.Peak_level',
        '-f', 'null', '-']
    out, last_loud = [], -10.0
    t = None
    for line in run(a).stderr.splitlines():
        m = re.search(r'pts_time:([-0-9.]+)', line)
        if m: t = float(m.group(1)); continue
        m = re.search(r'Peak_level=([-0-9.inf]+)', line)
        if m and t is not None:
            lvl = float(m.group(1)) if m.group(1) not in ('-inf', 'inf') else -200.0
            if lvl > -20:
                if t - last_loud > 0.5: out.append(t)
                last_loud = t
    return out

def decode_errors(path):
    return run(['ffmpeg', '-hide_banner', '-nostdin', '-v', 'error', '-i', path, '-f', 'null', '-']).stderr.strip()

def offsets(fl, bp):
    res = []
    for f in fl:
        near = min(bp, key=lambda b: abs(b - f), default=None)
        if near is not None and abs(near - f) < 0.5: res.append(near - f)
    return res

def median(x):
    s = sorted(x); return s[len(s) // 2] if s else None

def main():
    args = sys.argv[1:]
    src, out = args[0], args[1]
    audio = args[args.index('--audio') + 1] if '--audio' in args else 'aac'
    jpath = args[args.index('--json') + 1] if '--json' in args else None
    sd, ss = probe(src, True); od, os_ = probe(out, False)
    sv = [s for s in ss if s['codec_type'] == 'video']; sa = [s for s in ss if s['codec_type'] == 'audio']
    ov = [s for s in os_ if s['codec_type'] == 'video']; oa = [s for s in os_ if s['codec_type'] == 'audio']
    sf, of = flashes(src, True), flashes(out, False)
    sb, ob = beeps(src, True), beeps(out, False)
    so, oo = offsets(sf, sb), offsets(of, ob)
    r = {'source': {'duration': sd, 'video_frames': sv[0].get('nb_read_frames') if sv else None, 'flashes': len(sf), 'beeps': len(sb),
                    'sync_offset_ms': round(median(so) * 1000, 1) if so else None},
         'output': {'duration': od, 'streams': [(s['codec_type'], s['codec_name'], s.get('profile')) for s in os_],
                    'video_frames': ov[0].get('nb_read_frames') if ov else None, 'flashes': len(of), 'beeps': len(ob),
                    'sync_offset_ms': round(median(oo) * 1000, 1) if oo else None,
                    'sync_spread_ms': round((max(oo) - min(oo)) * 1000, 1) if oo else None}}
    fails = []
    if len(ov) != 1 or ov[0]['codec_name'] != 'h264': fails.append('output video stream')
    if len(oa) != 1 or oa[0]['codec_name'] != audio: fails.append('output audio stream (%s expected)' % audio)
    # --duration: erwartete Gesamtdauer, wenn sie nicht der von ffmpeg gelesenen HLS-Quelle entspricht (z. B. Video beginnt
    # später als der Ton: die MP4-Ausgabe reicht vom ersten Ton- bis zum letzten Video-/Ton-Sample)
    want = float(args[args.index('--duration') + 1]) if '--duration' in args else sd
    if abs(od - want) > 0.15: fails.append('duration %.3f vs %.3f' % (od, want))
    if sv and ov and sv[0].get('nb_read_frames') != ov[0].get('nb_read_frames'): fails.append('video frame count')
    if len(of) != len(sf) or len(sf) < 5: fails.append('flashes %d vs %d' % (len(of), len(sf)))
    if len(ob) != len(sb): fails.append('beeps %d vs %d' % (len(ob), len(sb)))
    if not so or not oo or abs(median(oo) - median(so)) > 0.030: fails.append('A/V sync differs from source')
    if oo and max(oo) - min(oo) > 0.030: fails.append('A/V sync drifts within the output')
    err = decode_errors(out)
    if err: fails.append('decode errors: ' + err[:200])
    r['fails'] = fails
    if jpath: open(jpath, 'w').write(json.dumps(r, indent=1) + '\n')
    print(('PASS ' if not fails else 'FAIL ') + out + ' ' + json.dumps(r))
    sys.exit(1 if fails else 0)

if __name__ == '__main__':
    main()
