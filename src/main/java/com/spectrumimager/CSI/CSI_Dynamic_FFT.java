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
 * This plugin continuously plots the profile along a line scan or a rectangle.
 * The profile is updated if the image changes, thus it can be used to monitor
 * the effect of a filter during preview.
 * Plot size etc. are set by Edit>Options>Profile Plot Options
 *
 * Restrictions:
 * - The plot window is not calibrated. Use Analyze>Plot Profile to get a
 *   spatially calibrated plot window where you can do measurements.
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
    private LUT img_lut; //LUT og plotImage
    private Thread bgThread;                //thread for plotting (in the background)
    private boolean doUpdate;               //tells the background thread to update
    //private boolean doUpdateBC;               //tells the background thread to update
    //private ContrastAdjuster bc = new ContrastAdjuster(); //for adjusting contrast

    /* Initialization and plot for the first time. Later on, updates are triggered by the listeners **/
    public void run(String arg) {
        imp = WindowManager.getCurrentImage();
        if (imp==null) {
            IJ.noImage(); return;
        }
        if (!isSelection()) {
            IJ.error("Dynamic Profiler","Line or Rectangular Selection Required"); return;
        }
        ImageProcessor ip = getFFT();  // get a profile

        if (ip==null) {                     // no profile?
            IJ.error("Dynamic Profiler","No Profile Obtained"); return;
        }
                                            // new plot window
        plotImage = new ImagePlus("Profile of "+imp.getShortTitle(), ip);
        plotImage.show();
        this.img_min = plotImage.getDisplayRangeMin();
        this.img_max = plotImage.getDisplayRangeMax();
        this.img_size_x = plotImage.getHeight();
        this.img_size_y = plotImage.getWidth();
        this.img_lut = plotImage.getProcessor().getLut();
        IJ.wait(50);
        positionPlotWindow();
                                            // thread for plotting in the background
        bgThread = new Thread(this, "Dynamic Profiler Plot");
        bgThread.setPriority(Math.max(bgThread.getPriority()-3, Thread.MIN_PRIORITY));
        bgThread.start();
        createListeners();
    }

    // these listeners are activated if the selection is changed in the corresponding ImagePlus
    public synchronized void mousePressed(MouseEvent e) { doUpdate = true; notify(); }   
    public synchronized void mouseDragged(MouseEvent e) { doUpdate = true; notify(); }
    public synchronized void mouseClicked(MouseEvent e) { doUpdate = true; notify(); }
    public synchronized void keyPressed(KeyEvent e) { doUpdate = true; notify(); }
    /*
    // For Brightness contrast
    public synchronized void adjustmentValueChanged(AdjustmentEvent e) {
        doUpdateBC = true;
        this.img_min = plotImage.getDisplayRangeMin();
        this.img_max = plotImage.getDisplayRangeMax();
        notify();
    }
    // For Brightness contrast
    public synchronized void itemStateChanged(ItemEvent e) {
        doUpdateBC = true; notify();
    }

     */
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
        /** Originally:
         * if (imp == this.imp) {}
         * new: if (imp == this.imp || imp == this.imp_copy)*/
        if (imp == this.imp ) {
            if (!isSelection())
                IJ.run(imp, "Restore Selection", "");
            doUpdate = true;
            notify();
        }
        /** added to attempt making sticky brightness contrast*/

        if (imp == this.plotImage ) {
            if (this.img_size_x == imp.getHeight()) {
                if (this.img_min != imp.getDisplayRangeMin()) {
                    //plotImage.setTitle("Changed min"); //for debugging
                    this.img_min = imp.getDisplayRangeMin();
                }
                if (this.img_max != imp.getDisplayRangeMax()) {
                    //plotImage.setTitle("Changed max"); //for debugging
                    this.img_max = imp.getDisplayRangeMax();
                }

                if (this.img_lut != imp.getProcessor().getLut()) {
                    //plotImage.setTitle("Changed LUT"); //for debugging
                    this.img_lut = imp.getProcessor().getLut();
                    //plotImage.setLut(this.img_lut);
                }
                //this.img_min = imp.getDisplayRangeMin();
                //this.img_max = imp.getDisplayRangeMax();
            }
            else {
                //plotImage.setTitle("Changed size"); for debugging
                plotImage.setDisplayRange(this.img_min, this.img_max);
                plotImage.setLut(this.img_lut);
            }
            this.img_size_x = imp.getHeight();
            this.img_size_y = imp.getWidth();
        }

    }

    // if either the plot image or the image we are listening to is closed, exit
    public void imageClosed(ImagePlus imp) {
        if (imp == this.imp || imp == plotImage) {
            removeListeners();
            closePlotImage();  //also terminates the background thread
        }
    }

    // the background thread for plotting.
    public void run() {
        while (true) {
            IJ.wait(50);  //delay to make sure the roi has been updated
            //ImageProcessor ip = getProfilePlot(); //working version 0.1.3
            ImageProcessor fft = getFFT();
            if (fft != null) plotImage.setProcessor(null, fft);
            plotImage.setDisplayRange(this.img_min, this.img_max);
            plotImage.setLut(this.img_lut);
            //plotImage.updateAndDraw();
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

    private synchronized void closePlotImage() {    //close the plot window and terminate the background thread
        bgThread.interrupt();
        plotImage.getWindow().close();
    }

    private void createListeners() {
        ImageWindow win = imp.getWindow();
        ImageCanvas canvas = win.getCanvas();
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addKeyListener(this);
        imp.addImageListener(this);
        plotImage.addImageListener(this);

    }

    private void removeListeners() {
        ImageWindow win = imp.getWindow();
        ImageCanvas canvas = win.getCanvas();
        canvas.removeMouseListener(this);
        canvas.removeMouseMotionListener(this);
        canvas.removeKeyListener(this);
        imp.removeImageListener(this);
        plotImage.removeImageListener(this);
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
        Roi roi = imp.getRoi(); //get ROI of image
        /* Calculate size */
        int maxN = Math.max(roi.getBounds().width, roi.getBounds().height);
        /* Find max power of 2 of size */
        //while(i<1.5*maxN) i*=2; //<-- fht example version
        int pix = (int) Math.pow(2,Math.ceil((Math.log(maxN) / Math.log(2)))); //<-- my version

        float[][] filter = new float[pix][pix]; //<-- create empty filter

        for (int n1=0; n1<pix; n1++) {
            for (int n2=0; n2<pix; n2++) {
                //int row = n1 % pix;
                //int col = n1 - (n1 % pix) * pix;
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

        Roi roiRect = this.imp_copy.getRoi();
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
        /** You are here */

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
    ImageProcessor getFFT() {
    //ImageProcessor getProfilePlot() {
        if (!isSelection()) return null;
        ImageProcessor ip = imp.getProcessor();
        Roi roi = imp.getRoi();
        if (ip == null || roi == null) return null; //these may change asynchronously
        if (roi.getType() == Roi.LINE)
            ip.setInterpolate(PlotWindow.interpolate);
        else
            ip.setInterpolate(false);
        /*
        ImageProcessor ip2;
        ImagePlus imp2;
        FFT profileP = new FFT();
        imp2 = profileP.forward(imp);
        */

        /** Get filter: */
        FloatProcessor filter = makeHammingWindow();
        /** Copy ROI*/
        this.imp_copy = new ImagePlus(imp.getTitle(), imp.getProcessor());
        imp_copy.setRoi(roi);
        /** Tile image roi*/
        tileImage(); //sets up cropped ip with power of 2
        /** Multiply image roi with filter*/
        this.imp_copy = new ImagePlus(imp.getTitle(), multiplyImages(this.ip_copy, filter));

        ImagePlus imp2 = FFT.forward(imp_copy); //works 0.1.3

        FHT fht = new FHT(ip_copy);
        fht.transform();
        //fht.swapQuadrants();
        //ImageProcessor fht_pix =fht.getRawPowerSpectrum();
        //fht_pix.log();
        ImageProcessor fht_pix = fht.getPowerSpectrum();

        if (imp2 == null) return null;

        return fht_pix;
        //return imp2.getProcessor();//plot.getProcessor();
        //return ip_copy;
    }


    /** returns true if there is a simple line selection or rectangular selection */
    boolean isSelection() {
        if (imp==null)
            return false;
        Roi roi = imp.getRoi();
        if (roi==null)
            return false;
        return roi.getType()==Roi.LINE || roi.getType()==Roi.RECTANGLE;
    }

}
