#!/usr/bin/env bash
# S3b: synthetisches DASH-VOD (eigener Inhalt: Testbild, weißer Blitz + 1-kHz-Ton alle 2 s, wie poc/p4a-hls), mit dem
# echten DASH-Packager von ffmpeg: SegmentTemplate + SegmentTimeline, $Number%05d$, zwei H.264-Darstellungen
# (320×180, 640×360) und eine AAC-Tonspur, fMP4 mit Init je Darstellung. Ausgabe: OUT/avc/manifest.mpd (+ Init/Stücke).
#   bash tests/java/dashdl/make-dash-fixtures.sh OUT
set -euo pipefail
OUT="${1:?output dir}"
rm -rf "$OUT"; mkdir -p "$OUT/avc"
V="testsrc2=size=640x360:rate=25,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='eq(mod(n\,50)\,0)',format=yuv420p"
A="aevalsrc=exprs='if(lt(mod(t\,2)\,0.04)\,0.8*sin(2*PI*1000*t)\,0)':sample_rate=48000:channel_layout=stereo"
( cd "$OUT/avc" && ffmpeg -hide_banner -v error -y -f lavfi -i "$V" -f lavfi -i "$A" -t 20 \
    -map 0:v -map 0:v -map 1:a -filter:v:0 scale=320:180 \
    -c:v libx264 -profile:v main -preset veryfast -crf 30 -g 50 -keyint_min 50 -sc_threshold 0 -bf 2 -threads 1 -flags:v +bitexact \
    -c:a aac -b:a 48k -ac 2 -ar 48000 -flags:a +bitexact -fflags +bitexact -map_metadata -1 \
    -f dash -seg_duration 4 -use_template 1 -use_timeline 1 -adaptation_sets "id=0,streams=v id=1,streams=a" \
    -init_seg_name 'init-$RepresentationID$.m4s' -media_seg_name 'chunk-$RepresentationID$-$Number%05d$.m4s' manifest.mpd )
# Fix 1 (Dauerprüfung): zweite echte Quelle mit $Number$ + @duration (ohne Zeitleiste), 18 s bei 4-s-Stücken – das
# letzte Stück ist kürzer als @duration und wird vom Periodenende angeschnitten (legitimer Fall für die Toleranz).
mkdir -p "$OUT/num"
( cd "$OUT/num" && ffmpeg -hide_banner -v error -y -f lavfi -i "$V" -f lavfi -i "$A" -t 18 \
    -map 0:v -map 1:a \
    -c:v libx264 -profile:v main -preset veryfast -crf 30 -g 50 -keyint_min 50 -sc_threshold 0 -bf 2 -threads 1 -flags:v +bitexact \
    -c:a aac -b:a 48k -ac 2 -ar 48000 -flags:a +bitexact -fflags +bitexact -map_metadata -1 \
    -f dash -seg_duration 4 -use_template 1 -use_timeline 0 -adaptation_sets "id=0,streams=v id=1,streams=a" \
    -init_seg_name 'init-$RepresentationID$.m4s' -media_seg_name 'chunk-$RepresentationID$-$Number%05d$.m4s' manifest.mpd )
# Fix 2 (Ausgabeprüfung): 10 s Medien in genau einem Stück je Spur (FFmpeg erlaubt höchstens ~2147 s; die Tests
# deklarieren im MPD wie im Prüfbefund timescale=1000, duration=3600000 bei einer Periode von 10 s)
mkdir -p "$OUT/long"
( cd "$OUT/long" && ffmpeg -hide_banner -v error -y -f lavfi -i "$V" -f lavfi -i "$A" -t 10 \
    -map 0:v -map 1:a \
    -c:v libx264 -profile:v main -preset veryfast -crf 30 -g 50 -keyint_min 50 -sc_threshold 0 -bf 2 -threads 1 -flags:v +bitexact \
    -c:a aac -b:a 48k -ac 2 -ar 48000 -flags:a +bitexact -fflags +bitexact -map_metadata -1 \
    -f dash -seg_duration 2000 -use_template 1 -use_timeline 0 -adaptation_sets "id=0,streams=v id=1,streams=a" \
    -init_seg_name 'init-$RepresentationID$.m4s' -media_seg_name 'chunk-$RepresentationID$-$Number%05d$.m4s' manifest.mpd )
( cd "$OUT" && ls avc > FILES.txt && ls num | sed 's|^|num/|' >> FILES.txt && ls long | sed 's|^|long/|' >> FILES.txt )
