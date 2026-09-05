"""Deterministic design previews that mirror WidgetRenderer's dp breakpoints.
These are design previews, not screenshots or proof of an Android build.
Usage: QUOTILE_PREVIEW_FONT=/path/to/NotoSansSC.ttf python render_previews.py
"""
from pathlib import Path
import os, math, json
from PIL import Image, ImageDraw, ImageFont
ROOT = Path(__file__).resolve().parent
FONT = os.environ.get('QUOTILE_PREVIEW_FONT')
if not FONT:
    candidates = [Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
                  Path('/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf')]
    FONT = next((str(p) for p in candidates if p.exists()), None)
if not FONT:
    raise SystemExit('Set QUOTILE_PREVIEW_FONT to a local Chinese sans-serif font.')
S = 3
FONTS = {}
def font(size):
    pixels = max(1, round(size*S))
    if pixels not in FONTS: FONTS[pixels] = ImageFont.truetype(FONT, pixels)
    return FONTS[pixels]
def measure(t,size): return font(size).getlength(t)/S

class Card:
    def __init__(self,w,h,dark=False,mode='demo',weekly=68,five=84):
        self.w,self.h,self.mode,self.weekly,self.five=w,h,mode,weekly,five
        self.old=mode in ('stale','expired') and (weekly is not None or five is not None)
        self.demo=mode=='demo'
        self.ink='#F0F2EF' if dark else '#25362F'
        self.secondary='#BAC4BD' if dark else '#68786F'
        self.muted='#99A69E' if dark else '#76867D'
        self.track='#35403A' if dark else '#E1E9E1'
        self.accent='#A4D4B9' if dark else '#609A7D'
        self.warning='#E3BD83' if dark else '#976B39'
        self.border='#39433D' if dark else '#E0E7DF'
        self.img=Image.new('RGBA',(w*S,h*S),(0,0,0,0)); self.d=ImageDraw.Draw(self.img)
        self.bounds=[]
        self.roundrect((.5,.5,w-.5,h-.5),min(22,h*.29),'#242D28' if dark else '#F6F8F2',self.border)
        if mode=='unconfigured': self.unconfigured()
        elif w>=250 and h>=116: self.detail()
        elif h>=116: self.narrow()
        else: self.compact()
        self.refresh()
    def roundrect(self,box,r,fill,outline=None):
        self.d.rounded_rectangle(tuple(round(v*S) for v in box),radius=r*S,fill=fill,outline=outline,width=2 if outline else 1)
    def text(self,t,x,y,size,color,maxw):
        if not t or maxw<=0 or y<size*.7 or y>self.h-2:return
        out=t
        if measure(out,size)>maxw:
            while out and measure(out+'…',size)>maxw:out=out[:-1]
            out=out+'…' if out else ''
        if not out:return
        b=self.d.textbbox((round(x*S),round(y*S)),out,font=font(size),anchor='ls')
        # Horizontal and bottom geometry is checked for every size/state case.
        assert x>=0 and x+measure(out,size)<=self.w+.1,(self.w,self.h,out,'x overflow')
        assert b[3]<=self.h*S,(self.w,self.h,out,'y overflow')
        self.bounds.append([out,*[round(v/S,2) for v in b]])
        self.d.text((round(x*S),round(y*S)),out,font=font(size),fill=color,anchor='ls')
    def value(self,a,x,y,size,maxw):
        if a is None:self.text('未提供',x,y-3,min(18,size*.53),self.secondary,maxw);return
        a=max(0,min(100,a))
        n='<0.1' if 0<a<.1 else '>99.9' if 99.9<a<100 else str(int(a)) if a==int(a) else f'{a:.1f}'
        total=measure(n,size)+measure('%',size*.43)+3
        if total>maxw:size*=maxw/total
        self.text(n,x,y,size,self.ink,maxw)
        end=x+measure(n,size)+2
        self.text('%',end,y-size*.06,size*.43,self.secondary,self.w-end)
    def bar(self,x,y,w,a):
        if w<=0:return
        self.roundrect((x,y,x+w,y+3),1.5,self.track)
        if a is None or a<=0:return
        self.roundrect((x,y,x+w*max(0,min(100,a))/100,y+3),1.5,self.warning if self.old else self.accent)
    def status(self):
        if self.demo: return '演示数据'
        if self.old: return '旧数据 · 待同步'
        if self.mode=='stale' and self.weekly is None and self.five is None: return '暂未获取额度'
        return '等待首次同步' if self.mode=='waiting' else '9/5 10:24 更新'
    def reset(self,five=False):
        if self.mode=='expired':return '已到期 · 待同步'
        if self.mode=='waiting' or (self.five if five else self.weekly) is None:return '重置未提供'
        return '今天 12:00 重置' if five else '9/8 11:30 重置'
    def compact(self):
        w,h=self.w,self.h;pad=10 if w<200 else 14;right=w-48;avail=right-pad;tiny=h<56
        labely=12 if tiny else h*.5-15;numy=32 if tiny else h*.5+15
        size=24 if tiny else min(35,h*.48)
        label=('演示·周余量' if self.demo else '旧·周余量' if self.old else '周余量') if w<200 else '每周剩余'
        if avail<65:label='演示' if self.demo else '旧数据' if self.old else '周余量'
        self.text(label,pad,labely,10,self.warning if self.old else self.secondary,avail)
        self.value(self.weekly,pad,numy,size,min(98,avail))
        if w>=250:
            x=pad+110
            self.text(self.reset(),x,17 if tiny else numy-18,10 if tiny else 11,self.secondary,right-x)
            self.text(self.status(),x,31 if tiny else numy-1,10,self.warning if self.old else self.muted,right-x)
        elif w>=175:
            x=pad+76
            self.text('演示' if self.demo else '旧数据' if self.old else '剩余',x,numy-2,10,self.warning if self.old else self.muted,right-x)
        self.bar(pad,h-(4 if tiny else 8),avail,self.weekly)
    def detail(self):
        w,h=self.w,self.h;pad=14 if w<320 else 18;top=12 if h<135 else 18;gap=22 if w<320 else 28
        col=(w-pad*2-gap)/2;right=pad+col+gap
        numy=min(h-50,top+(72 if h>=180 else 60));size=38 if h<135 else 52 if h>=180 else 44
        self.text('每周剩余',pad,top+11,11,self.secondary,col)
        self.text('5小时剩余',right,top+11,11,self.secondary,w-46-right)
        self.value(self.weekly,pad,numy,size,col);self.value(self.five,right,numy,size,col)
        by=numy+11
        self.bar(pad,by,col,self.weekly);self.bar(right,by,col,self.five)
        self.text(self.reset(),pad,by+19,10,self.secondary,col)
        self.text(self.reset(True),right,by+19,10,self.secondary,col)
        fy=h-max(8,top*.65)
        self.text(self.status(),pad,fy,10,self.warning if self.old else self.muted,w*.60-pad)
        caption='Pro 5x · 北京时间';fw=min(w*.36,measure(caption,9))
        self.text(caption,w-pad-fw,fy,9,self.muted,fw)
    def narrow(self):
        w,h=self.w,self.h;pad=12 if w<160 else 16;content=w-pad*2
        self.text('每周剩余',pad,26,11,self.secondary,w-46-pad)
        y=64 if h<145 else 74
        self.value(self.weekly,pad,y,36 if w<160 else 44,content)
        self.bar(pad,y+12,content,self.weekly)
        if h>=142:self.text(self.reset(),pad,y+33,10,self.secondary,content)
        self.text(self.status(),pad,h-12,10,self.warning if self.old else self.muted,content)
    def unconfigured(self):
        pad=10 if self.w<180 else 16;avail=self.w-48-pad
        if self.h<56:self.text('待连接',pad,24,14,self.ink,avail)
        else:
            center=self.h*.5
            self.text('余量 · 待连接',pad,center-3,12 if self.w<180 else 16,self.ink,avail)
            self.text('点按设置' if self.w<180 else '点按设置，连接用量来源',pad,center+17,10,self.secondary,avail)
    def refresh(self):
        cx,cy=self.w-22,self.h*.5 if self.h<116 else 22
        self.d.ellipse(((cx-13)*S,(cy-13)*S,(cx+13)*S,(cy+13)*S),fill=self.track)
        self.d.arc(((cx-5)*S,(cy-5)*S,(cx+5)*S,(cy+5)*S),35,322,fill=self.secondary,width=4)
        pts=[(cx+1.3,cy-5.8),(cx+4.5,cy-4.1),(cx+4.6,cy-7.5)]
        self.d.line([(round(x*S),round(y*S)) for x,y in pts],fill=self.secondary,width=4,joint='curve')

def sheet_text(d,text,x,y,size,color='#25362F'):
    d.text((x*S,y*S),text,font=font(size),fill=color,anchor='ls')
def paste(sheet,c,x,y):sheet.alpha_composite(c.img,(x*S,y*S))

def main():
    sheet=Image.new('RGBA',(850*S,745*S),'#E8EDE5');d=ImageDraw.Draw(sheet)
    sheet_text(d,'余量',40,51,26)
    sheet_text(d,'设计预览 · 演示数字 · 非设备截图',40,79,12,'#68786F')
    for dark,x in ((False,40),(True,460)):
        sheet_text(d,'浅色' if not dark else '深色',x,127,13)
        sheet_text(d,'高 × 宽  1 × 5  /  350 × 64 dp',x,158,11,'#68786F')
        c=Card(350,64,dark);paste(sheet,c,x,176)
        sheet_text(d,'高 × 宽  2 × 5  /  350 × 150 dp',x,283,11,'#68786F')
        c=Card(350,150,dark);paste(sheet,c,x,301)
        sheet_text(d,'缩窄后自动保留核心信息',x,496,11,'#68786F')
        paste(sheet,Card(160,150,dark),x,515)
        paste(sheet,Card(110,64,dark),x+204,515)
        sheet_text(d,'160 × 150 dp',x,687,10,'#68786F')
        sheet_text(d,'110 × 64 dp',x+204,603,10,'#68786F')
    sheet_text(d,'网格尺寸由启动器决定；拖动边框后，内容按实际宽高重新排版。',40,722,11,'#68786F')
    sheet.convert('RGB').save(ROOT/'quotile_design_preview.png')
    states=Image.new('RGBA',(850*S,487*S),'#E8EDE5');d=ImageDraw.Draw(states)
    sheet_text(d,'状态设计',40,45,23)
    sheet_text(d,'设计预览 · 演示情景 · 非设备截图',40,71,12,'#68786F')
    for i,(label,mode,a,b) in enumerate([('连接前','unconfigured',None,None),('来源未提供余量','normal',None,None),('同步失败，保留旧值','stale',68,84),('超过重置时间，等待新快照','expired',68,84)]):
        x=40+(i%2)*420;y=106+(i//2)*187
        sheet_text(d,label,x,y,12,'#68786F')
        paste(states,Card(350,150,i%2==1,mode,a,b),x,y+13)
    states.convert('RGB').save(ROOT/'quotile_state_preview.png')
    cases=0
    for w in (110,160,174,175,249,250,300,350,700):
        for h in (40,55,56,64,115,116,134,135,142,145,150,300):
            for mode in ('demo','normal','stale','expired','waiting','unconfigured'):
                for a in (None,0,.01,.6,68,99.99,100):
                    Card(w,h,False,mode,a,a);cases+=1
    report={'previewOnly':True,'androidRuntimeExecuted':False,'checkedLayouts':cases,'checks':['text right bounds','text bottom bounds','Java breakpoint mirror'],'limitations':'Python font metrics approximate Android; compilation and real launcher rendering are separate checks.'}
    (ROOT/'preview_checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))
if __name__=='__main__':main()
