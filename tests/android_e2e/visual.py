"""Read real screenshots when Firefox omits closed-shadow children from Android AX.
No DOM access or product invocation; all input remains real UI Automator taps.
Icon paths are Material Icons already shipped by the tested production player.
"""
import csv
import io
import json
from functools import lru_cache
from pathlib import Path
import subprocess

import cairosvg
import cv2
import numpy as np
from PIL import Image

ICONS = json.loads(Path(__file__).with_name("icons.json").read_text())
SIZES = {"I.player": 23, "P.close": 22, "P.pause": 32, "P.play": 32, "I.download": 22, "P.cc": 22}
REGIONS = {"I.player": (0, .15, 1, .93), "P.close": (0, 0, .25, .30),
           "P.pause": (.30, .30, .70, .70), "P.play": (.30, .30, .70, .70),
           "I.download": (.65, 0, 1, .30), "P.cc": (0, .65, 1, 1)}

@lru_cache(maxsize=64)
def template(key, size):
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="white" d="' + ICONS[key] + '"/></svg>'
    data = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    # RGB alone is white even in transparent pixels. Composite onto black first.
    rgba = Image.open(io.BytesIO(data)).convert("RGBA")
    bg = Image.new("RGBA", rgba.size, (0, 0, 0, 255))
    bg.alpha_composite(rgba)
    return np.array(bg.convert("L"))

def locate(image, key, density, threshold=None):
    if threshold is None: threshold = .74 if key == 'I.player' else .82
    gray = np.array(image.convert("L"))
    h,w = gray.shape
    left,top,right,bottom = REGIONS[key]
    x0,y0,x1,y1 = int(w*left),int(h*top),int(w*right),int(h*bottom)
    region=gray[y0:y1,x0:x1]
    best=None
    for scale in (.97, 1, 1.03):
        size=round(SIZES[key]*density*scale)
        if min(region.shape) < size: continue
        scores=cv2.matchTemplate(region,template(key,size),cv2.TM_CCOEFF_NORMED)
        _,score,_,xy=cv2.minMaxLoc(scores)
        if best is None or score>best["score"]:
            best={"key":key,"score":round(score,4),"x":x0+xy[0]+size//2,
                  "y":y0+xy[1]+size//2,"size":size}
    return best if best and best["score"]>=threshold else None

def words(image):
    # TSV boxes describe rendered pixels, independently of browser DOM/AX.
    data=subprocess.run(["tesseract","stdin","stdout","--psm","11","tsv"],
        input=png(image),
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=15).stdout.decode()
    result=[]
    for row in csv.DictReader(io.StringIO(data),delimiter="\t"):
        if row["text"].strip() and float(row["conf"])>=35:
            result.append({"text":row["text"].strip(),"confidence":float(row["conf"]),
                **{k:int(row[k]) for k in ("left","top","width","height","block_num","par_num","line_num")}})
    return result

def png(image):
    out=io.BytesIO()
    image.save(out,format="PNG")
    return out.getvalue()

def phrase(image, label):
    groups={}
    for row in words(image):
        groups.setdefault((row["block_num"],row["par_num"],row["line_num"]),[]).append(row)
    for rows in groups.values():
        text=" ".join(r["text"] for r in rows)
        if label.casefold() in text.casefold():
            return {"x":sum(r["left"]+r["width"]/2 for r in rows)/len(rows),
                    "y":sum(r["top"]+r["height"]/2 for r in rows)/len(rows),
                    "text":text}
    return None

if __name__=="__main__":
    import sys
    # Calibration is a read-only check against a captured real emulator frame.
    image=Image.open(sys.argv[1])
    hit=locate(image,"I.player",2.625)
    print("Diagnostic best visual candidate:",hit,flush=True)
    assert hit and 900<hit["x"]<1050 and 1200<hit["y"]<1400,hit
    from PIL import ImageDraw
    negative=image.copy()
    ImageDraw.Draw(negative).rectangle((940,1250,1030,1340),fill="white")
    assert locate(negative,"I.player",2.625) is None,"False match outside the removed real icon"
    print("PASS real screenshot positive/negative icon calibration:",hit)
