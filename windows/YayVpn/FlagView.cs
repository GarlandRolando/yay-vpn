using System.Windows;
using System.Windows.Media;
namespace YayVpn;
// Compact vector flags: works without regional-flag emoji support on Windows.
sealed class FlagView : FrameworkElement {
    internal string Code {get;set;}="";
    protected override void OnRender(DrawingContext d){
        double w=ActualWidth,h=ActualHeight;d.PushClip(new RectangleGeometry(new Rect(0,0,w,h)));
        void Rect(Brush b,double x,double y,double width,double height)=>d.DrawRectangle(b,null,new Rect(x*w,y*h,width*w,height*h));
        void Dot(Brush b,double x,double y,double radius)=>d.DrawEllipse(b,null,new Point(x*w,y*h),radius*h,radius*h);
        void Line(Brush b,double x,double y,double x2,double y2,double width)=>d.DrawLine(new Pen(b,width*h),new(x*w,y*h),new(x2*w,y2*h));
        void Poly(Brush b,params double[] xy){var g=new StreamGeometry();using(var c=g.Open()){c.BeginFigure(new(xy[0]*w,xy[1]*h),true,true);for(int i=2;i<xy.Length;i+=2)c.LineTo(new(xy[i]*w,xy[i+1]*h),true,false);}d.DrawGeometry(b,null,g);}
        void Star(double x,double y,double radius,Brush brush){var v=new double[20];for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5,r=i%2==0?radius:radius*.4;v[i*2]=x+Math.Cos(a)*r*h/w;v[i*2+1]=y+Math.Sin(a)*r;}Poly(brush,v);}
        Rect(Brushes.White,0,0,1,1);
        switch(Code){
        case "SG":Rect(Brushes.Crimson,0,0,1,.5);Dot(Brushes.White,.2,.25,.18);Dot(Brushes.Crimson,.24,.22,.15);for(int i=0;i<5;i++){double a=-Math.PI/2+i*2*Math.PI/5;Star(.39+Math.Cos(a)*.065,.25+Math.Sin(a)*.1,.035,Brushes.White);}break;
        case "JP":Dot(Brushes.Crimson,.5,.5,.29);break;
        case "DE":Rect(Brushes.Black,0,0,1,1.0/3);Rect(Brushes.Crimson,0,1.0/3,1,1.0/3);Rect(Brushes.Gold,0,2.0/3,1,1.0/3);break;
        case "ES":Rect(Brushes.Crimson,0,0,1,.25);Rect(Brushes.Gold,0,.25,1,.5);Rect(Brushes.Crimson,0,.75,1,.25);Rect(Brushes.Crimson,.28,.4,.08,.23);break;
        case "US":for(int i=0;i<13;i+=2)Rect(Brushes.Crimson,0,i/13.0,1,1.0/13);Rect(Brushes.Navy,0,0,.43,7.0/13);for(int row=0;row<5;row++)for(int col=0;col<6;col++)Star(.04+col*.065,.045+row*.105,.023,Brushes.White);break;
        case "GB":Rect(Brushes.Navy,0,0,1,1);Line(Brushes.White,0,0,1,1,.22);Line(Brushes.White,0,1,1,0,.22);Line(Brushes.Crimson,0,0,1,1,.08);Line(Brushes.Crimson,0,1,1,0,.08);Rect(Brushes.White,.39,0,.22,1);Rect(Brushes.White,0,.35,1,.3);Rect(Brushes.Crimson,.44,0,.12,1);Rect(Brushes.Crimson,0,.4,1,.2);break;
        case "AU":Rect(Brushes.Navy,0,0,1,1);Line(Brushes.White,0,0,.5,.5,.08);Line(Brushes.White,0,.5,.5,0,.08);Rect(Brushes.White,.2,0,.1,.5);Rect(Brushes.White,0,.2,.5,.1);Rect(Brushes.Crimson,.23,0,.04,.5);Rect(Brushes.Crimson,0,.23,.5,.04);Star(.23,.75,.12,Brushes.White);Star(.75,.2,.08,Brushes.White);Star(.65,.5,.07,Brushes.White);Star(.86,.46,.07,Brushes.White);Star(.76,.8,.08,Brushes.White);break;
        case "BR":Rect(Brushes.ForestGreen,0,0,1,1);Poly(Brushes.Gold,.08,.5,.5,.08,.92,.5,.5,.92);Dot(Brushes.Navy,.5,.5,.26);Line(Brushes.White,.35,.44,.66,.56,.055);break;
        case "IN":Rect(Brushes.DarkOrange,0,0,1,1.0/3);Rect(Brushes.ForestGreen,0,2.0/3,1,1.0/3);d.DrawEllipse(null,new Pen(Brushes.Navy,h*.025),new(w*.5,h*.5),h*.13,h*.13);for(int i=0;i<12;i++){double a=i*Math.PI/6;Line(Brushes.Navy,.5,.5,.5+Math.Cos(a)*.13*h/w,.5+Math.Sin(a)*.13,.009);}break;
        case "HK":Rect(Brushes.Crimson,0,0,1,1);for(int i=0;i<5;i++){double a=-Math.PI/2+i*2*Math.PI/5;Dot(Brushes.White,.5+Math.Cos(a)*.12,.5+Math.Sin(a)*.18,.12);}break;
        case "KR":Dot(Brushes.Crimson,.5,.43,.2);Dot(Brushes.Navy,.5,.55,.16);for(int i=0;i<3;i++){Line(Brushes.Black,.14,.19+i*.075,.27,.28+i*.075,.035);Line(Brushes.Black,.73,.62+i*.075,.86,.71+i*.075,.035);}break;
        case "ZA":Rect(Brushes.Crimson,0,0,1,.5);Rect(Brushes.Navy,0,.5,1,.5);Poly(Brushes.White,0,0,.43,.38,1,.38,1,.62,.43,.62,0,1);Poly(Brushes.ForestGreen,0,.05,.43,.43,1,.43,1,.57,.43,.57,0,.95);Poly(Brushes.Gold,0,.13,.36,.5,0,.87);Poly(Brushes.Black,0,.22,.27,.5,0,.78);break;
        }
        d.Pop();
    }
}
