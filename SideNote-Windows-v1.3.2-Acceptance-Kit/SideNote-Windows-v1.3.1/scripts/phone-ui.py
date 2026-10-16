import subprocess,json,re,xml.etree.ElementTree as ET,hashlib
from pathlib import Path
DEVICE='192.168.1.2:42907'
BASE=Path(__file__).resolve().parent.parent/'src-tauri/target/physical-phone-check'
BASE.mkdir(parents=True,exist_ok=True)
def adb(*args):
 r=subprocess.run(['adb','-s',DEVICE,*map(str,args)],capture_output=True,check=True,timeout=35)
 return r.stdout
def dump():
 adb('shell','rm','-f','/sdcard/sidenote-check.xml')
 result=adb('shell','uiautomator','dump','/sdcard/sidenote-check.xml')
 if b'dumped' not in result.lower():raise RuntimeError('Phone UI was not idle; no fresh hierarchy was produced')
 data=adb('exec-out','cat','/sdcard/sidenote-check.xml');(BASE/'screen.xml').write_bytes(data)
 return ET.fromstring(data)
def tap(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
 adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
def click(text):
 nodes=list(dump().iter('node'))
 node=next((n for n in nodes if n.get('text')==text or n.get('content-desc')==text),None)
 if node is None:raise RuntimeError('UI control missing: '+text)
 tap(node)
def notes():
 data=adb('exec-out','run-as','com.sidedeck.notes','cat','/data/user/0/com.sidedeck.notes/shared_prefs/side_notes.xml')
 root=ET.fromstring(data)
 return json.loads(next((n.text for n in root if n.get('name')=='notes'),'[]'))
def labels(root):
 return [{'text':n.get('text') or n.get('content-desc'),'class':n.get('class'),'bounds':n.get('bounds')} for n in root.iter('node') if n.get('text') or n.get('content-desc') or n.get('class')=='android.widget.EditText']
def screenshot(name):
 (BASE/name).write_bytes(adb('exec-out','screencap','-p'))
if __name__=='__main__':
 import sys
 action=sys.argv[1] if len(sys.argv)>1 else 'dump'
 if action=='dump':print(json.dumps(labels(dump()),ensure_ascii=True))
 elif action=='click':click(sys.argv[2]);print(json.dumps(labels(dump()),ensure_ascii=True))
 elif action=='notes':print(json.dumps({'count':len(notes())}))
 elif action=='screenshot':screenshot(sys.argv[2])
