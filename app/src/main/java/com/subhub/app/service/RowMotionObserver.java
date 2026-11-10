package com.subhub.app.service;

/** Worker-owned shadow observer. Retains descriptors only, never source pixels or camera state. */
final class RowMotionObserver {
    static final int ROWS = 160;
    static final class Scope {
        final long captureEpoch, document;
        final int window, sourceWidth, sourceHeight;
        Scope(long captureEpoch,long document,int window,int sourceWidth,int sourceHeight) {
            this.captureEpoch=captureEpoch; this.document=document; this.window=window;
            this.sourceWidth=sourceWidth; this.sourceHeight=sourceHeight;
        }
        boolean valid() { return captureEpoch>=0 && document>=0 && window>=0 && sourceWidth>0 && sourceHeight>0; }
        boolean matches(Scope other) { return other!=null && captureEpoch==other.captureEpoch
                && document==other.document && window==other.window
                && sourceWidth==other.sourceWidth && sourceHeight==other.sourceHeight; }
    }
    static final class Sample {
        final boolean accepted;
        final long previousTime, currentTime;
        final double dy;
        final int bands;
        Sample(boolean accepted, long previousTime, long currentTime, double dy, int bands) {
            this.accepted=accepted; this.previousTime=previousTime; this.currentTime=currentTime;
            this.dy=dy; this.bands=bands;
        }
    }
    private double[][] previous;
    private long time;
    private Scope scope;
    private int width, height, top, bottom;

    Sample observe(int[] pixels, int width, int height, int top, int bottom,
            Scope scope, long timestamp, boolean recentScrollSignal) {
        if (pixels == null || width < 4 || height < ROWS || width > 512 || height > 512
                || (long)width*height != pixels.length || top < 0 || bottom > height
                || bottom-top < ROWS || timestamp < 0 || scope==null || !scope.valid()) {
            clear(); return new Sample(false, -1, timestamp, 0, 0);
        }
        if (previous != null && timestamp <= time) {
            clear(); return new Sample(false, -1, timestamp, 0, 0);
        }
        double[][] current=describe(pixels,width,top,bottom);
        boolean compatible=previous!=null && scope.matches(this.scope)
                && this.width==width && this.height==height && this.top==top && this.bottom==bottom
                && timestamp>time && timestamp-time<=750;
        long previousTime=compatible ? time : -1;
        RowMotionEstimator.Result result=compatible && recentScrollSignal
                ? RowMotionEstimator.estimate(previous,current) : null;
        previous=current; time=timestamp; this.scope=scope;
        this.width=width; this.height=height; this.top=top; this.bottom=bottom;
        return new Sample(result!=null && result.accepted,previousTime,timestamp,
                result!=null && result.accepted ? result.dy*(bottom-top)/(double)ROWS : 0,
                result==null ? 0 : result.agreeingBands);
    }

    static double[][] describe(int[] pixels,int width,int top,int bottom) {
        double[][] rows=new double[ROWS][4];
        for(int row=0;row<ROWS;row++) {
            int y=top+(int)((row+.5)*(bottom-top)/ROWS);
            for(int column=0;column<4;column++) {
                int left=column*width/4, right=(column+1)*width/4;
                long sum=0;
                for(int x=left;x<right;x++) {
                    int color=pixels[y*width+x];
                    sum+=3*((color>>>16)&255)+6*((color>>>8)&255)+(color&255);
                }
                rows[row][column]=sum/(10.0*(right-left));
            }
        }
        return rows;
    }

    void clear() { previous=null; time=0; }
}
