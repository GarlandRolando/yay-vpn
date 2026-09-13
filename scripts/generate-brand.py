"""Rebuild Yay's original vector and platform icon assets (Python + Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw
import json
ROOT = Path(__file__).resolve().parents[1]
POINTS=[(24,22),(40,22),(50,42),(60,22),(76,22),(57,57),(57,80),(43,80),(43,57)]
COLOR='#E53945'
def write(path,data):
 path=ROOT/path;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
def png(path,size):
 path=ROOT/path;path.parent.mkdir(parents=True,exist_ok=True)
 im=Image.new('RGB',(size*4,size*4),COLOR);ImageDraw.Draw(im).polygon([(x*size*4/100,y*size*4/100) for x,y in POINTS],fill='white')
 im=im.resize((size,size),Image.Resampling.LANCZOS);im.save(path);return im
path='M'+ ' L'.join(f'{x},{y}' for x,y in POINTS)+' Z'
write('branding/yay-logo.svg',f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="{COLOR}"/><path d="{path}" fill="white"/></svg>\n')
png('branding/yay-logo.png',1024)
im=png('windows/YayVpn/Assets/yay-logo.png',256)
im.save(ROOT/'windows/YayVpn/Assets/yay.ico',sizes=[(n,n) for n in [16,24,32,48,64,128,256]])
write('android/app/src/main/res/drawable/ic_yay.xml',f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" android:viewportWidth="100" android:viewportHeight="100"><path android:fillColor="{COLOR}" android:pathData="M0,0 H100 V100 H0 Z"/><path android:fillColor="#FFFFFF" android:pathData="{path}"/></vector>\n')
write('android/app/src/main/res/drawable/ic_shield.xml',f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="100" android:viewportHeight="100"><path android:fillColor="#FFFFFF" android:pathData="{path}"/></vector>\n')
base=Path('apple/App/Assets.xcassets')
write(base/'Contents.json',json.dumps({'info':{'author':'xcode','version':1}},indent=2))
png(base/'YayLogo.imageset/yay-logo.png',256)
write(base/'YayLogo.imageset/Contents.json',json.dumps({'images':[{'filename':'yay-logo.png','idiom':'universal'}],'info':{'author':'xcode','version':1}},indent=2))
images=[{'filename':'icon-1024.png','idiom':'universal','platform':'ios','size':'1024x1024'}]
for size in [16,32,128,256,512]:
 for scale in [1,2]:images.append({'filename':f'icon-{size*scale}.png','idiom':'mac','size':f'{size}x{size}','scale':f'{scale}x'})
for pixels in [16,32,64,128,256,512,1024]:png(base/f'AppIcon.appiconset/icon-{pixels}.png',pixels)
write(base/'AppIcon.appiconset/Contents.json',json.dumps({'images':images,'info':{'author':'xcode','version':1}},indent=2))
