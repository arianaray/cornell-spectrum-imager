package com.spectrumimager.CSI;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.Rectangle;
/** For brightness/contrast adjustment: */
//import java.awt.event.AdjustmentListener;
//import java.awt.event.ItemListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.ItemEvent;
import ij.plugin.frame.ContrastAdjuster;
/** End brightness/contrast */

import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Line;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.gui.ProfilePlot;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.plugin.FFT;
import ij.process.FHT;
import ij.process.LUT;
import ij.util.Tools;
import ij.process.FloatProcessor;
import ij.plugin.filter.FFTCustomFilter;

/**
 * This plugin continuously plots the FFT of a rectangle.
 * The profile is updated if the image changes, thus it can be used to monitor
 * the effect of a filter during preview.
 *
 * Restrictions:
 *
 * By Wayne Rasband and Michael Schmid
 * Version 2009-Jun-09: obeys 'fixed y axis scale' in Edit>Options>Profile Plot Options
 */
public class CSI_Dynamic_FFT
        implements PlugIn, MouseListener, MouseMotionListener, KeyListener, ImageListener, Runnable {
    //MouseListener, MouseMotionListener, KeyListener: to detect changes to the selection of an ImagePlus
    //ImageListener: listens to changes (updateAndDraw) and closing of an image
    //Runnable: for background thread
    private ImagePlus imp;                  //the ImagePlus that we listen to and the last one
    /** Ariana's addition for FFT filtering*/
    private ImagePlus imp_copy; //the ImagePlus that the filter is applied to
    private ImageProcessor ip_copy;
    private ImagePlus plotImage;            //where we plot the profile
    private double img_min; //display minimum of plotimage
    private double img_max; //display maximum of plotImage
    private int img_size_x; //size of plotImage
    private int img_size_y; //size of plotImage
    private int slice_num; //which slice are we on.
    private LUT img_lut; //LUT og plotImage
    private Thread bgThread;                //thread for plotting (in the background)
    private boolean doUpdate;               //tells the background thread to update

    /* Initialization and plot for the first time. Later on, updates are triggered by the listeners **/
    public void run(String arg) {
        imp = WindowManager.getCurrentImage();
        if (imp==null) {
            IJ.noImage(); return;
        }
        if (!isSelection()) {
            //IJ.error("Dynamic FFT","Rectangular Selection Required");
            Roi roi = new Roi(0,0,imp.getWidth(),imp.getHeight());
            imp.setRoi(roi);
            return;

        }

        //System.out.println("Initial roi: " + imp.getRoi());

        // new plot window
        FHT fft = getFFT();
        fft.transform();
        IJ.showProgress(0.0);
        fft.setShowProgress(false);
        plotImage = new ImagePlus();
        plotImage.setProcessor(fft.getPowerSpectrum());
        plotImage.show();
        plotImage.setProperty("FHT",fft);
        plotImage.setProperty("Info","tee hee\n");
        plotImage.setTitle("FFT of " + imp.getShortTitle());

        /* Set global variables */
        this.img_min = plotImage.getDisplayRangeMin();  //Max intensity
        this.img_max = plotImage.getDisplayRangeMax(); //Min intensity
        this.img_size_x = plotImage.getHeight(); //Height
        this.img_size_y = plotImage.getWidth(); //Width
        this.img_lut = plotImage.getProcessor().getLut(); //LUT
        this.slice_num = imp.getCurrentSlice(); //Slice

        IJ.wait(50);
        positionPlotWindow();
        // thread for plotting in the background
        bgThread = new Thread(this, "Dynamic FFT Plot");
        bgThread.setPriority(Math.max(bgThread.getPriority()-3, Thread.MIN_PRIORITY));
        bgThread.start();
        createListeners(this.imp);
    }

    // these listeners are activated if the selection is changed in the corresponding ImagePlus
    public synchronized void mousePressed(MouseEvent e) { doUpdate = true; notify(); }
    public synchronized void mouseDragged(MouseEvent e) { doUpdate = true; notify(); }
    public synchronized void mouseClicked(MouseEvent e) { doUpdate = true; notify(); }
    public synchronized void keyPressed(KeyEvent e) { doUpdate = true; notify(); }

    // unused listeners concerning actions in the corresponding ImagePlus
    public void mouseReleased(MouseEvent e) {}
    public void mouseExited(MouseEvent e) {}
    public void mouseEntered(MouseEvent e) {}
    public void mouseMoved(MouseEvent e) {}
    public void keyTyped(KeyEvent e) {}
    public void keyReleased(KeyEvent e) {}
    public void imageOpened(ImagePlus imp) {}

    // this listener is activated if the image content is changed (by imp.updateAndDraw)
    public synchronized void imageUpdated(ImagePlus imp) {
        boolean sliceChanged = false;
        if (imp == this.imp ) {
            if (!isSelection()) {
                Roi roi = new Roi(0, 0, imp.getWidth(), imp.getHeight());
                imp.setRoi(roi);
            }

            //System.out.println("imageUpdated roi: " + imp.getRoi());

                //IJ.run(imp, "Restore Selection", "");
            if (this.slice_num != imp.getCurrentSlice()) {
                sliceChanged = true;
            }
            doUpdate = true;
            notify();
        }
        /* Make brightness/contrast and LUT sticky */

        if (imp == this.plotImage ) {
            if (this.img_size_x == imp.getHeight() && sliceChanged == false) {
                if (this.img_min != imp.getDisplayRangeMin()) {
                    this.img_min = imp.getDisplayRangeMin();
                }
                if (this.img_max != imp.getDisplayRangeMax()) {
                    this.img_max = imp.getDisplayRangeMax();
                }

                if (this.img_lut != imp.getProcessor().getLut()) {
                    this.img_lut = imp.getProcessor().getLut();
                }
            }
            else {
                plotImage.setDisplayRange(this.img_min, this.img_max);
                plotImage.setLut(this.img_lut);
            }
            this.img_size_x = imp.getHeight();
            this.img_size_y = imp.getWidth();
            //this.img_lut = imp.getProcessor().getLut();
        }

    }

    // if either the plot image or the image we are listening to is closed, exit
    public void imageClosed(ImagePlus imp_close) {
        /*
        if (imp == this.imp || imp == this.plotImage) {
            removeListeners();
            closePlotImage();  //also terminates the background thread
        }
        */
        if (imp_close == this.imp) {
            removeListeners(plotImage);
            closePlotImage(plotImage);
        }
        if (imp_close == this.plotImage) {
            removeListeners(imp);
            //closePlotImage(plotImage);
        }
        //removeListeners(imp_close);
        //closePlotImage(imp_close);
    }

    // the background thread for plotting.
    public void run() {
        while (true) {
            IJ.wait(50);  //delay to make sure the roi has been updated

            if (!isSelection()) {
                Roi roi = new Roi(0, 0, imp.getWidth(), imp.getHeight());
                imp.setRoi(roi);
                //System.out.println("Run roi: " + imp.getRoi());
            }

            FHT fft = getFFT();
            fft.transform();
            IJ.showProgress(0.0);
            fft.setShowProgress(false); //you are here ***
            plotImage.setProcessor(fft.getPowerSpectrum());
            plotImage.setDisplayRange(this.img_min, this.img_max);
            plotImage.setLut(this.img_lut);
            plotImage.setProperty("FHT",fft);
            plotImage.setProperty("Info","tee hee");
            plotImage.setCalibration(imp.getCalibration());
            synchronized(this) {
                if (doUpdate) {
                    doUpdate = false;  //and loop again
                } else {
                    try {
                        wait();
                    }  //notify wakes up the thread
                    catch(InterruptedException e) { //interrupted tells the thread to exit
                        return;
                    }
                }
            }
        }
    }

    private synchronized void closePlotImage(ImagePlus imp_close) {    //close the plot window and terminate the background thread
        bgThread.interrupt();
        imp_close.getWindow().close();
        //plotImage.getWindow().close();
        //imp.getWindow().close();
    }

    private void createListeners(ImagePlus imp_listen) {
        ImageWindow win = imp_listen.getWindow();
        ImageCanvas canvas = win.getCanvas();
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addKeyListener(this);
        imp_listen.addImageListener(this);
        //plotImage.addImageListener(this);

    }

    private void removeListeners(ImagePlus imp_listen) {
        ImageWindow win = imp_listen.getWindow();
        ImageCanvas canvas = win.getCanvas();
        canvas.removeMouseListener(this);
        canvas.removeMouseMotionListener(this);
        canvas.removeKeyListener(this);
        imp_listen.removeImageListener(this);
        //plotImage.removeImageListener(this);
    }

    /** Place the plot window to the right of the image window */
    void positionPlotWindow() {
        IJ.wait(500);
        if (plotImage==null || imp==null) return;
        ImageWindow pwin = plotImage.getWindow();
        ImageWindow iwin = imp.getWindow();
        if (pwin==null || iwin==null) return;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension plotSize = pwin.getSize();
        Dimension imageSize = iwin.getSize();
        if (plotSize.width==0 || imageSize.width==0) return;
        Point imageLoc = iwin.getLocation();
        int x = imageLoc.x+imageSize.width+10;
        if (x+plotSize.width>screen.width)
            x = screen.width-plotSize.width;
        pwin.setLocation(x, imageLoc.y);
        ImageCanvas canvas = iwin.getCanvas();
        canvas.requestFocus();
    }

    /** make a Hamming window filter*/
    //float[][] makeHammingWindow() {
    FloatProcessor makeHammingWindow() {
        Roi roi = imp.getRoi();

        /* Calculate size */
        int maxN = Math.max(roi.getBounds().width, roi.getBounds().height);
        //System.out.println("Hamming window roi: " + roi);
        /* Find max power of 2 of size */
        int pix = (int) Math.pow(2,Math.ceil((Math.log(maxN) / Math.log(2)))); //<-- my version

        float[][] filter = new float[pix][pix]; //<-- create empty filter

        for (int n1=0; n1<pix; n1++) {
            for (int n2=0; n2<pix; n2++) {
                float r1 = (float) (2 * n1) / pix - 1;
                float r2 = (float) (2 * n2) / pix - 1;
                float r3 = (float) (Math.pow(r1, 2) + Math.pow(r2, 2));
                r3 = (float) Math.sqrt(r3);
                float w3 = (float) ((float) 0.5 * (Math.cos(Math.PI * r3) + 1));
                if (r3 >= 0 && r3 < 1) {
                    filter[n1][n2] = 1 * w3;
                } else {
                    filter[n1][n2] = 0;
                }
            }
        }

        return new FloatProcessor(filter);
    }

    void tileImage() {

        //this.imp_copy.setCalibration(this.imp.getCalibration());
        Roi roiRect = this.imp_copy.getRoi();
        //System.out.println("tile image roi: " + roiRect);
        int maxN = Math.max(roiRect.getBounds().width, roiRect.getBounds().height);
        /** Calculate power of 2 size */
        int pix = (int) Math.pow(2,Math.ceil((Math.log(maxN) / Math.log(2))));
        /** Fit image into power of 2 size (from FFT_Filter.java) */
        Rectangle fitRect = new Rectangle();
        fitRect.x = (int) Math.round((pix - roiRect.getBounds().width)/2.0);
        fitRect.y = (int) Math.round((pix - roiRect.getBounds().height)/2.0);
        fitRect.width = roiRect.getBounds().width;
        fitRect.height = roiRect.getBounds().height;
        /** Pad image to power of 2 size */
        tileMirror(imp_copy.getProcessor(), pix, pix, fitRect.x, fitRect.y);
    }

    /** Puts imageprocessor (ROI) into a new imageprocessor of size width x height y at position (x,y).
     The image is mirrored around its edges to avoid wrap around effects of the FFT. */
    /** Ariana note: Copied from FFT_Filter.java*/
    void tileMirror(ImageProcessor ip, int width, int height, int x, int y) {
        if (x < 0 || x > (width -1) || y < 0 || y > (height -1)) {
            IJ.error("Image to be tiled is out of bounds.");
            return;
        }

        this.ip_copy = ip.createProcessor(width, height);

        ImageProcessor ip2 = ip.crop();
        int w2 = ip2.getWidth();
        int h2 = ip2.getHeight();

        //how many times does ip2 fit into ipout?
        int i1 = (int) Math.ceil(x / (double) w2);
        int i2 = (int) Math.ceil( (width - x) / (double) w2);
        int j1 = (int) Math.ceil(y / (double) h2);
        int j2 = (int) Math.ceil( (height - y) / (double) h2);

        //tile
        if ( (i1%2) > 0.5)
            ip2.flipHorizontal();
        if ( (j1%2) > 0.5)
            ip2.flipVertical();

        for (int i=-i1; i<i2; i += 2) {
            for (int j=-j1; j<j2; j += 2) {
                this.ip_copy.insert(ip2, x-i*w2, y-j*h2);
            }
        }

        ip2.flipHorizontal();
        for (int i=-i1+1; i<i2; i += 2) {
            for (int j=-j1; j<j2; j += 2) {
                this.ip_copy.insert(ip2, x-i*w2, y-j*h2);
            }
        }

        ip2.flipVertical();
        for (int i=-i1+1; i<i2; i += 2) {
            for (int j=-j1+1; j<j2; j += 2) {
                this.ip_copy.insert(ip2, x-i*w2, y-j*h2);
            }
        }

        ip2.flipHorizontal();
        for (int i=-i1; i<i2; i += 2) {
            for (int j=-j1+1; j<j2; j += 2) {
                this.ip_copy.insert(ip2, x-i*w2, y-j*h2);
            }
        }

    }

    ImageProcessor multiplyImages(ImageProcessor ip_copy, FloatProcessor filter) {

        int w2 = ip_copy.getWidth();
        int h2 = ip_copy.getHeight();
        double val;

        for (int i = 0; i < h2; i++) {
            for (int j = 0; j < w2; j++) {
                val = ip_copy.getPixelValue(i, j)*filter.getPixelValue(i, j);
                ip_copy.putPixelValue(i, j, val);
            }
        }

        return ip_copy;
    }

    /** get a profile, analyze it and return a plot (or null if not possible) */
    FHT getFFT() {
        ImageProcessor ip = imp.getProcessor();
        Roi roi = imp.getRoi();
        //System.out.println("getFFT roi: " + roi);

        /** Get filter: */
        FloatProcessor filter = makeHammingWindow();
        /** Copy ROI*/
        this.imp_copy = new ImagePlus(imp.getTitle(), imp.getProcessor());
        imp_copy.setRoi(roi);
        /** Tile image roi*/
        tileImage(); //sets up cropped ip with power of 2
        /** Multiply image roi with filter*/
        this.imp_copy = new ImagePlus(imp.getTitle(), multiplyImages(this.ip_copy, filter));
        /** Take FFT and return*/

        return new FHT(ip_copy);

    }


    /** returns true if there is a simple line selection or rectangular selection */
    boolean isSelection() {
        if (imp==null)
            return false;
        Roi roi = imp.getRoi();
        if (roi==null)
            return false;
        if (roi.getBounds().width == 0 || roi.getBounds().height == 0)
            return false;
            //roi = new Roi(0,0,imp.getWidth(),imp.getHeight());
        return roi.getType()==Roi.LINE || roi.getType()==Roi.RECTANGLE;
    }

}
