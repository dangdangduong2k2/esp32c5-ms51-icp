"""Generate the instructional phone-to-controller GIF (Pillow required)."""
from pathlib import Path
import math
from PIL import Image, ImageDraw

root=Path(__file__).resolve().parents[1]
class ScaledDraw:
    def __init__(self,image): self.draw=ImageDraw.Draw(image)
    def __getattr__(self,name):
        def call(coords,*args,**kwargs):
            coords=tuple(v*2 for v in coords)
            for key in ("width","radius"):
                if key in kwargs: kwargs[key]*=2
            return getattr(self.draw,name)(coords,*args,**kwargs)
        return call
frames=[]
for i in range(80):
    t=i/80
    progress=min(1,t/.5) if t<.65 else max(0,1-(t-.65)/.35)
    ease=(1-math.cos(math.pi*progress))/2
    image=Image.new("RGB",(960,560),"white"); d=ScaledDraw(image)
    navy="#10354a";blue="#007fbd";pale="#e5f4fb"
    # Opaque controller enclosure, display and physical keys.
    d.rounded_rectangle((290,78,461,202),radius=18,fill=pale,outline=navy,width=4)
    d.rounded_rectangle((302,90,449,190),radius=10,fill=navy)
    for y,color in [(106,"#f06979"),(142,"#35c899")]:
        for x in (314,332,350):
            d.line((x,y,x+10,y,x+10,y+12,x,y+12,x,y,x,y+25,x+10,y+25,x+10,y+12),fill=color,width=3)
    for y in (119,158):
        d.ellipse((394,y-8,410,y+8),fill="#d5e6ee")
        d.ellipse((421,y-8,437,y+8),fill="#d5e6ee")
    # Phone moves towards the fixed controller, then returns smoothly.
    x=round(25+175*ease)
    d.rounded_rectangle((x,51,x+73,229),radius=15,fill=navy)
    d.rounded_rectangle((x+6,64,x+67,214),radius=8,fill=pale)
    d.rounded_rectangle((x+24,57,x+49,61),radius=2,fill="#b6dff1")
    d.line((x+26,222,x+47,222),fill="#b6dff1",width=3)
    # Bluetooth mark and proximity indication.
    cx=x+36;cy=139
    d.line((cx,cy-24,cx,cy+24,cx+15,cy+10,cx-12,cy-12),fill=blue,width=4)
    d.line((cx,cy-24,cx+15,cy-10,cx-12,cy+12),fill=blue,width=4)
    if ease>.8:
        for r in (12,23):d.arc((274-r,140-r,274+r,140+r),-55,55,fill=blue,width=3)
    frames.append(image)
output=root/'mobile/android/app/src/main/assets/phone-near-device.gif'
frames[0].save(output,save_all=True,append_images=frames[1:],duration=40,loop=0,optimize=True)
print(str(output),output.stat().st_size)
