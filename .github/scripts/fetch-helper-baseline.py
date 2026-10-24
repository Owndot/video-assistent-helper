"""Retrieve only pinned non-signing baseline source blobs; never clone signing history."""
import base64,hashlib,json,os,pathlib,sys,urllib.request
ref="b05fd2de60d27d368eda60ff32be8b361a695f45"
base="https://api.github.com/repos/Owndot/video-assistent-helper"
def get(path):
    req=urllib.request.Request(base+path,headers={"Authorization":"Bearer "+os.environ["GH_TOKEN"],"Accept":"application/vnd.github+json"})
    with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)
out=pathlib.Path(sys.argv[1])
tree=get("/git/trees/"+ref+"?recursive=1")
if tree.get("truncated"):raise RuntimeError("Incomplete source tree")
for entry in tree["tree"]:
    path=entry["path"]
    if entry["type"]!="blob" or not(path.startswith("app/src/") or path in {"build.gradle","settings.gradle","gradle.properties"}):continue
    if ".." in pathlib.PurePosixPath(path).parts:raise RuntimeError("Invalid source path")
    blob=get("/git/blobs/"+entry["sha"])
    data=base64.b64decode(blob["content"])
    if len(data)>2*1024*1024:raise RuntimeError("Unexpected source blob size")
    if hashlib.sha1(b"blob "+str(len(data)).encode()+b"\0"+data).hexdigest()!=entry["sha"]:raise RuntimeError("Source blob mismatch")
    target=out/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
print("Fetched pinned app sources and root build configuration only")
