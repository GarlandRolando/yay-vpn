package com.yay.vpn;
import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Lightweight stylized world silhouette, rendered locally without map tracking or downloads. */
final class WorldMapView extends View {
    boolean connected;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final float[][] LAND={
        {7,24,13,16,21,12,28,16,32,23,29,31,25,33,24,42,20,46,16,39,12,38,9,31},
        {21,44,25,47,29,49,32,57,30,66,27,76,24,82,23,71,20,61,20,52},
        {31,9,38,7,39,17,35,25,31,20},
        {43,27,47,20,51,19,52,25,57,29,53,35,48,34,46,39,42,34},
        {45,38,53,37,59,44,58,54,55,64,51,70,47,59,46,49,42,44},
        {53,23,58,18,67,17,72,12,81,17,92,21,93,29,86,33,83,39,79,42,76,48,73,44,70,37,66,41,63,49,59,42,59,33,55,31},
        {74,48,77,52,80,54,79,57,75,54}, {81,59,88,56,93,64,91,71,83,73,78,68},
        {94,73,96,69,96,76,93,80}, {60,60,61,66,59,70,58,65}, {86,37,88,32,89,39,87,43},
        {77,58,83,59,86,62,81,62}, {5,89,16,86,33,88,46,85,61,88,77,85,93,88,96,94,6,94}
    };
    WorldMapView(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float w=getWidth(),h=getHeight(),mapH=w*.6f,top=(h-mapH)/2;
        int rgb=connected?0x004ce5a0:0x00f34b59;
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0x16000000|rgb);
        for(int i=0;i<=10;i++){c.drawLine(w*i/10,top,w*i/10,top+mapH,p);c.drawLine(0,top+mapH*i/10,w,top+mapH*i/10,p);}
        p.setStyle(Paint.Style.FILL);p.setColor(0x43000000|rgb);
        for(float[] points:LAND){Path path=new Path();path.moveTo(w*points[0]/100,top+mapH*points[1]/100);for(int i=2;i<points.length;i+=2)path.lineTo(w*points[i]/100,top+mapH*points[i+1]/100);path.close();c.drawPath(path,p);}
        p.setStyle(Paint.Style.STROKE);p.setColor(0x25000000|rgb);for(int radius=70;radius<190;radius+=40)c.drawCircle(w/2,h/2,radius*getResources().getDisplayMetrics().density,p);
    }
}
