  function lensMap(w, h, r, rimA, waveA, zoom, half) {
    const R0 = Math.min(w, h) / 2;
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const ctx = c.getContext('2d');
    const img = ctx.createImageData(w, h), d = img.data;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const px = x + 0.5, py = y + 0.5;
        const cx = Math.min(Math.max(px, r), w - r), cy = Math.min(Math.max(py, r), h - r);
        let vx = px - cx, vy = py - cy, edge;
        const len = Math.hypot(vx, vy);
        if (len > 0) { edge = r - len; vx /= len; vy /= len; }
        else {
          const dl = px - r, dr = w - r - px, dt = py - r, db = h - r - py, m = Math.min(dl, dr, dt, db);
          edge = r + m;
          vx = m === dl ? -1 : m === dr ? 1 : 0;
          vy = vx ? 0 : (m === dt ? -1 : 1);
        }
        // u: 0 am Rand … 1 in der Mitte; Verschiebung nach innen = das Bild wird an dieser Stelle "herausgezogen"
        const u = Math.max(0, Math.min(1, edge / R0)), q = 1 - u;
        // (Steigung bleibt überall unter 1 – sonst überschlägt sich das Bild und Text erscheint gespiegelt)
        const off = rimA * q * q + waveA * Math.sin(2 * Math.PI * 1.25 * u) * q;
        const ox = (-(px - w / 2) * (1 - 1 / zoom) - vx * off) / half;
        const oy = (-(py - h / 2) * (1 - 1 / zoom) - vy * off) / half;
        const i = (y * w + x) * 4;
        d[i] = Math.round(128 + 127 * Math.max(-1, Math.min(1, ox)));
        d[i + 1] = Math.round(128 + 127 * Math.max(-1, Math.min(1, oy)));
        d[i + 2] = 128;
        d[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return c.toDataURL();
  }