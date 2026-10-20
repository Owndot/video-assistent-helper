// Verschiebungskarte für eine abgerundete Glasfläche: am Rand wird das Bild dahinter nach innen gebogen (Linse)
function lensMap(w, h, r, bezel, mag) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const ctx = c.getContext('2d');
  const img = ctx.createImageData(w, h);
  const d = img.data;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const px = x + 0.5, py = y + 0.5;
    const cx = Math.min(Math.max(px, r), w - r), cy = Math.min(Math.max(py, r), h - r);
    let vx = px - cx, vy = py - cy;
    const len = Math.hypot(vx, vy);
    let edge;            // Abstand zum Rand (innen)
    if (len > 0) { edge = r - len; vx /= len; vy /= len; }
    else {
      const dl = px - r, dr = w - r - px, dt = py - r, db = h - r - py;
      const m = Math.min(dl, dr, dt, db);
      edge = r + m;
      vx = m === dl ? -1 : m === dr ? 1 : 0; vy = m === dt ? -1 : m === db ? 1 : 0;
      if (vx && vy) vy = 0;
    }
    let k = 0;
    if (edge < bezel && edge >= 0) { const t = 1 - edge / bezel; k = t * t * (3 - 2 * t); }   // weich zum Rand hin
    // Rand: nach innen ziehen (Licht wird gebogen) · Mitte: leicht vergrößern (Lupe)
    const mx = (px - w / 2) / (w / 2), my = (py - h / 2) / (h / 2);
    const ox = -vx * k - mx * mag, oy = -vy * k - my * mag;
    const i = (y * w + x) * 4;
    d[i] = Math.round(128 + 127 * Math.max(-1, Math.min(1, ox)));
    d[i + 1] = Math.round(128 + 127 * Math.max(-1, Math.min(1, oy)));
    d[i + 2] = 128; d[i + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  return c.toDataURL();
}
