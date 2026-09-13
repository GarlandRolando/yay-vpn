using System.Windows;
using System.Windows.Media;
namespace YayVpn;
sealed class WorldMap : FrameworkElement {
 internal bool Active;
 static readonly double[][] Land={
new double[]{7,24,13,16,21,12,28,16,32,23,29,31,25,33,24,42,20,46,16,39,12,38,9,31},
new double[]{21,44,25,47,29,49,32,57,30,66,27,76,24,82,23,71,20,61,20,52},
new double[]{31,9,38,7,39,17,35,25,31,20},
new double[]{43,27,47,20,51,19,52,25,57,29,53,35,48,34,46,39,42,34},
new double[]{45,38,53,37,59,44,58,54,55,64,51,70,47,59,46,49,42,44},
new double[]{53,23,58,18,67,17,72,12,81,17,92,21,93,29,86,33,83,39,79,42,76,48,73,44,70,37,66,41,63,49,59,42,59,33,55,31},
new double[]{74,48,77,52,80,54,79,57,75,54},
new double[]{81,59,88,56,93,64,91,71,83,73,78,68},
new double[]{94,73,96,69,96,76,93,80},
new double[]{60,60,61,66,59,70,58,65},
new double[]{86,37,88,32,89,39,87,43},
new double[]{77,58,83,59,86,62,81,62},
new double[]{5,89,16,86,33,88,46,85,61,88,77,85,93,88,96,94,6,94}
 };
 protected override void OnRender(DrawingContext dc){
  var color=Active?Color.FromArgb(75,76,229,160):Color.FromArgb(75,243,75,89);
  foreach(var polygon in Land){var g=new StreamGeometry();using(var c=g.Open()){c.BeginFigure(new Point(polygon[0]*ActualWidth/100,polygon[1]*ActualHeight/100),true,true);for(int i=2;i<polygon.Length;i+=2)c.LineTo(new Point(polygon[i]*ActualWidth/100,polygon[i+1]*ActualHeight/100),true,false);}dc.DrawGeometry(new SolidColorBrush(color),null,g);}
 }
}
