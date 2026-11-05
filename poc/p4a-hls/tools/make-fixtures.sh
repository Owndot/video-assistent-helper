#!/usr/bin/env bash
# P4a: synthetic HLS-VOD test material (own content only: test pattern, white flash + 1 kHz beep every 2 s).
# The flash and the beep start at the same instant in the source, so A/V sync of any remuxed output can be measured
# from the content itself (tools/analyze.py). Output: OUT/<fixture>/master.m3u8 (+ media playlists, segments).
#   bash poc/p4a-hls/tools/make-fixtures.sh OUT
set -euo pipefail
OUT="${1:?output dir}"
rm -rf "$OUT"; mkdir -p "$OUT"
DUR=20
V="testsrc2=size=320x180:rate=25,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='eq(mod(n\,50)\,0)',format=yuv420p"
A="aevalsrc=exprs='if(lt(mod(t\,2)\,0.04)\,0.8*sin(2*PI*1000*t)\,0)':sample_rate=48000:channel_layout=stereo"
X264=(-c:v libx264 -profile:v main -preset veryfast -crf 30 -g 50 -keyint_min 50 -sc_threshold 0 -bf 2 -threads 1 -flags:v +bitexact)
AAC=(-c:a aac -b:a 48k -ac 2 -ar 48000 -flags:a +bitexact)
COMMON=(-hide_banner -v error -y)
SRC=(-f lavfi -i "$V" -f lavfi -i "$A" -t "$DUR")
hls() {   # hls NAME TYPE(mpegts|fmp4) [extra args...]
  local name=$1 type=$2; shift 2
  mkdir -p "$OUT/$name"
  ( cd "$OUT/$name" && ffmpeg "${COMMON[@]}" "${SRC[@]}" "$@" -fflags +bitexact -map_metadata -1 -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type "$type" \
      -hls_flags independent_segments -hls_segment_filename 's%v_%03d.'"$([ "$type" = fmp4 ] && echo m4s || echo ts)" \
      $([ "$type" = fmp4 ] && echo -hls_fmp4_init_filename init.mp4) -master_pl_name master.m3u8 'v%v.m3u8' )
}
# 1/3: one variant, audio muxed into the same segments
hls ts_muxed mpegts -map 0:v -map 1:a "${X264[@]}" "${AAC[@]}" -var_stream_map 'v:0,a:0'
hls fmp4_muxed fmp4 -map 0:v -map 1:a "${X264[@]}" "${AAC[@]}" -var_stream_map 'v:0,a:0'
# 2/4: separate audio rendition (EXT-X-MEDIA TYPE=AUDIO), video-only variant
hls ts_sep mpegts -map 0:v -map 1:a "${X264[@]}" "${AAC[@]}" -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes,language:de'
hls fmp4_sep fmp4 -map 0:v -map 1:a "${X264[@]}" "${AAC[@]}" -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes,language:de'
# 2b/4b: separate audio whose first sample is 0.3 s after the first video frame (PTS offset kept in the stream, content
# still aligned): per-track timestamp normalisation would shift the beeps by 300 ms against the flashes
hls ts_sep_offset mpegts -map 0:v -map 1:a -filter:a atrim=start=0.3:end=20 "${X264[@]}" "${AAC[@]}" -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes'
hls fmp4_sep_offset fmp4 -map 0:v -map 1:a -filter:a atrim=start=0.3:end=20 "${X264[@]}" "${AAC[@]}" -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes'
# 4c: fMP4 without edit lists (timeline only in tfdt/composition offsets, as other packagers write it)
hls fmp4_sep_noedts fmp4 -map 0:v -map 1:a "${X264[@]}" "${AAC[@]}" -hls_segment_options use_editlist=0 \
  -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes'
# 5: two video qualities sharing one separate audio rendition (quality selection)
hls ts_multi mpegts -map 0:v -map 0:v -map 1:a -filter:v:1 scale=160:90 "${X264[@]}" "${AAC[@]}" \
  -var_stream_map 'v:0,agroup:aud v:1,agroup:aud a:0,agroup:aud,default:yes'
# 6: fMP4 with a separate Opus rendition (Opus is only allowed where a test proves it)
hls fmp4_opus_sep fmp4 -map 0:v -map 1:a "${X264[@]}" -c:a libopus -b:a 48k -ac 2 -ar 48000 -flags:a +bitexact \
  -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes'
# 7: ten minutes, tiny picture (memory per sample: ~43 000 samples, measured on the device)
mkdir -p "$OUT/ts_long600"
( cd "$OUT/ts_long600" && ffmpeg "${COMMON[@]}" -f lavfi -i "testsrc2=size=64x36:rate=25,format=yuv420p" -f lavfi -i "sine=frequency=500:sample_rate=48000" \
    -t 600 -map 0:v -map 1:a -c:v libx264 -profile:v main -preset ultrafast -crf 45 -g 50 -bf 2 -threads 1 -c:a aac -b:a 16k -ac 1 \
    -fflags +bitexact -map_metadata -1 -f hls -hls_time 6 -hls_playlist_type vod -hls_segment_type mpegts -hls_segment_filename 's%v_%03d.ts' \
    -var_stream_map 'v:0,agroup:aud a:0,agroup:aud,default:yes' -master_pl_name master.m3u8 'v%v.m3u8' )
# ffmpeg writes CODECS only for some layouts; keep its playlists unchanged (the parser must cope with what exists)
find "$OUT" -type f | sort | while read -r f; do printf '%s %s\n' "$(sha256sum < "$f" | cut -c1-16)" "${f#$OUT/}"; done > "$OUT/FILES.txt"
du -sh "$OUT"
