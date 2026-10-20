#!/usr/bin/env bash
# Erzeugt alle Test-Streams für die Java-Tests (braucht ffmpeg + openssl). Ergebnis: srv/ und src.mp4/src.webm
set -euo pipefail
cd "$(dirname "$0")"
rm -rf srv && mkdir -p srv/enc srv/enc2 srv/fm srv/br srv/live srv/d1 srv/d2 srv/d3 srv/d4 srv/d5 srv/ck/hl srv/pub
q="-loglevel error -y"
ffmpeg $q -f lavfi -i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440 -t 12 -c:v libx264 -preset veryfast -g 50 -c:a aac -shortest src.mp4
ffmpeg $q -i src.mp4 -c:v libvpx -b:v 400k -c:a libvorbis src.webm
# HLS: verschlüsselt (AES-128) mit zwei Qualitätsstufen
openssl rand 16 > srv/enc/key.bin
printf 'key.bin\nsrv/enc/key.bin\n%s\n' "$(openssl rand -hex 16)" > keyinfo.txt
ffmpeg $q -i src.mp4 -c copy -hls_time 2 -hls_playlist_type vod -hls_key_info_file keyinfo.txt -hls_segment_filename 'srv/enc/hi_%03d.ts' srv/enc/hi.m3u8
ffmpeg $q -i src.mp4 -vf scale=320:180 -c:v libx264 -preset veryfast -g 50 -c:a aac -hls_time 2 -hls_playlist_type vod -hls_segment_filename 'srv/enc/lo_%03d.ts' srv/enc/lo.m3u8
printf '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x180\nlo.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=640x360\nhi.m3u8\n' > srv/enc/master.m3u8
# HLS: verschlüsselt ohne IV in der Playlist (IV = Teilnummer)
cp srv/enc/key.bin srv/enc2/
printf 'key.bin\nsrv/enc2/key.bin\n' > keyinfo2.txt
ffmpeg $q -i src.mp4 -c copy -hls_time 2 -hls_playlist_type vod -hls_key_info_file keyinfo2.txt -hls_segment_filename 'srv/enc2/s_%03d.ts' srv/enc2/s.m3u8
sed 's/,IV=0x[0-9a-fA-F]*//' srv/enc2/s.m3u8 > srv/enc2/noiv.m3u8
# HLS: fMP4 mit getrennter Tonspur
ffmpeg $q -i src.mp4 -map 0:v -c copy -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_type fmp4 -hls_fmp4_init_filename v_init.mp4 -hls_segment_filename 'srv/fm/v_%03d.m4s' srv/fm/v.m3u8
ffmpeg $q -i src.mp4 -map 0:a -c copy -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_type fmp4 -hls_fmp4_init_filename a_init.mp4 -hls_segment_filename 'srv/fm/a_%03d.m4s' srv/fm/a.m3u8
printf '#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="de",DEFAULT=YES,URI="a.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="aud"\nv.m3u8\n' > srv/fm/master.m3u8
# HLS: eine Datei mit Byte-Bereichen, und dasselbe als "Livestream" (ohne Ende)
ffmpeg $q -i src.mp4 -c copy -hls_time 2 -hls_playlist_type vod -hls_flags single_file srv/br/one.m3u8
grep -v ENDLIST srv/br/one.m3u8 | sed 's#^one.ts#../br/one.ts#' > srv/live/live.m3u8
# DASH: Template+Timeline, Template+Dauer, eine Datei je Spur, mit DRM-Kennung, als Livestream
ffmpeg $q -i src.mp4 -map 0:v -map 0:a -c copy -f dash -seg_duration 2 -use_template 1 -use_timeline 1 srv/d1/manifest.mpd
ffmpeg $q -i src.mp4 -map 0:v -map 0:a -c copy -f dash -seg_duration 2 -use_template 1 -use_timeline 0 srv/d2/manifest.mpd
ffmpeg $q -i src.mp4 -map 0:v -map 0:a -c copy -f dash -seg_duration 2 -single_file 1 srv/d3/manifest.mpd
cp srv/d1/* srv/d4/ && sed -i 's#<Representation id="\([01]\)"#<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/><Representation id="\1"#' srv/d4/manifest.mpd
cp srv/d1/* srv/d5/ && sed -i 's/type="static"/type="dynamic"/' srv/d5/manifest.mpd
# Geschützte Dateien (Cookie nötig) für Cookie- und Ende-zu-Ende-Tests
cp src.mp4 srv/ck/film.mp4; cp src.webm srv/ck/film.webm; cp src.webm srv/pub/film.webm
(cd srv/ck/hl && ffmpeg $q -i ../../../src.mp4 -c copy -hls_time 3 -hls_playlist_type vod -hls_segment_filename 's_%02d.ts' own.m3u8 && sed 's#^s_#http://localhost:8792/ck/hl/s_#' own.m3u8 > foreign.m3u8)
rm -f keyinfo.txt keyinfo2.txt
echo "Test-Streams fertig."
