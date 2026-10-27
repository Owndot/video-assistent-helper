"""One evaluator for measured and synthetic playback traces (no browser imports).

Rows retain the existing sampler columns, then append paused, stable-latched,
and playbackRate: [wall_ms, position_s, bridge, source_paused, source_muted,
video_muted, spinner, readyState, seeking, paused, stable, playbackRate].
"""
import math


def evaluate_playback(rows, start, opened_at, rate=1.0):
    errors = []
    if not rows or not all(len(s) >= 12 and all(math.isfinite(s[i]) for i in (0, 1, 11)) for s in rows):
        return {'ok': False, 'errors': ['missing/nonfinite samples']}
    first = next((i for i, s in enumerate(rows) if s[10]), None)
    if first is None:
        return {'ok': False, 'errors': ['no lifecycle-defined stable phase']}
    # Once stability starts, keep EVERY later sample, including seeks and bad positions.
    stable = rows[first:]
    span = (stable[-1][0] - stable[0][0]) / 1000
    progress = stable[-1][1] - stable[0][1]
    if len(stable) < 8 or span < 0.65:
        errors.append('insufficient observation (8 samples / 650ms minimum)')
    if stable[0][0] < opened_at:
        errors.append('stable phase predates opening')
    if not start-0.25 <= stable[0][1] <= start+(stable[0][0]-opened_at)/1000*rate+0.35:
        errors.append('wrong initial stable position')
    back = forward = 0.0
    for i, s in enumerate(stable):
        if s[2] or s[7] < 2 or s[8] or s[9] or not s[10] or abs(s[11]-rate) > 0.001:
            errors.append('stable lifecycle regressed')
        elapsed = (s[0] - stable[0][0]) / 1000
        drift = s[1] - stable[0][1] - elapsed*rate
        # Existing 200/350ms jump budgets; wall time, never a fixed timer cadence.
        if drift < -0.25 or drift > 0.35:
            errors.append('cumulative playback drift')
        if i:
            previous = stable[i-1]
            dt = (s[0]-previous[0])/1000
            if dt <= 0 or dt > 0.25:
                errors.append('nonchronological/sparse samples')
            change = s[1]-previous[1]
            back = max(back, -change)
            forward = max(forward, change-dt*rate)
    if back >= 0.2 or forward >= 0.35:
        errors.append('backward/forward jump')
    if progress < max(0.2, span*rate-0.25):
        errors.append('no sufficient positive playback progress')
    total_elapsed = (stable[-1][0]-opened_at)/1000
    if not start+span*rate-0.25 <= stable[-1][1] <= start+total_elapsed*rate+0.35:
        errors.append('final position inconsistent with start/wall time')
    return {'ok': not errors, 'errors': sorted(set(errors)), 'samples': len(stable),
            'span': span, 'progress': progress, 'back': back, 'forward': forward,
            'first': stable[0][1], 'last': stable[-1][1], 'elapsed': total_elapsed}
