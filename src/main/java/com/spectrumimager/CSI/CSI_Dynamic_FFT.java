package com.spectrumimager.CSI;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import javax.swing.event.ChangeListener;
import javax.swing.event.ChangeEvent;
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
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.ImagePanel;
import ij.gui.Line;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.gui.ProfilePlot;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.plugin.ImagesToStack;
import ij.plugin.FFT;
import ij.process.FHT;
import ij.process.LUT;
import ij.util.Tools;
import ij.process.FloatProcessor;
import ij.plugin.filter.FFTCustomFilter;

import javax.swing.*;

/**
 * This plugin continuously plots the FFT of a rectangle.
 * The profile is updated if the image changes, thus it can be used to monitor
 * the effect of a filter during preview.
 *
 * By Ariana Ray
 */
public class CSI_Dynamic_FFT
        implements PlugIn, MouseListener, MouseMotionListener, KeyListener, ImageListener, ChangeListener, ActionListener, Runnable {
    //MouseListener, MouseMotionListener, KeyListener: to detect changes to the selection of an ImagePlus
    //ImageListener: listens to changes (updateAndDraw) and closing of an image
    //Runnable: for background thread
    private ImagePlus imp;                  //the ImagePlus that we listen to and the last one
    /** Ariana's addition for FFT filtering*/
    private ImagePlus imp_copy; //the ImagePlus that the filter is applied to
    private FloatProcessor ip_copy;
    private ImageStack imp_copy_stack; //for stack processing
    private ImageStack plotImage_stack; //for stack display
    private ImagePlus plotImage;            //where we plot the profile
    private double img_min; //display minimum of plotimage
    private double img_max; //display maximum of plotImage
    private int img_size_x; //size of plotImage
    private int img_size_y; //size of plotImage
    private int slice_num; //which slice are we on.
    private LUT img_lut; //LUT og plotImage
    private Thread bgThread;                //thread for plotting (in the background)
    private boolean doUpdate;               //tells the background thread to update
    private JCheckBox chkFilter; //checkbox for doFilter.
    private JCheckBox chkStack; //checkbox for doStack.
    private JButton butIFFT; //button for inverseFFT
    private JButton butFFT; //button for forwardFFT
    private boolean doFilter = true; //for doing filter
    private boolean doStack_ifft = true; //for doing iFFT of whole stack at once.
    private boolean isStack;

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
        if (imp.isStack()) {
            isStack = true;
        }
        else {
            isStack = false;
            //imp.setTitle("not stack");
        }

        //System.out.println("Initial roi: " + imp.getRoi());

        // Take FFT
        FHT fft = getFFT();
        fft.transform();
        fft.originalBitDepth = imp.getBitDepth();
        IJ.showProgress(0.0);
        fft.setShowProgress(false);

        // Make new image to show FFT
        plotImage = new ImagePlus();
        plotImage.setProcessor(fft.getPowerSpectrum());
        plotImage.show();
        plotImage.setProperty("FHT",fft);
        //* New addition 1.7 //
        plotImage.setProperty("Info","tee hee\n "+String.valueOf(imp.getBitDepth())+"\n");
        plotImage.setProperty("bitdepth",imp.getBitDepth());

        // Make ImageWindow to hold plotImage and components
        ImageWindow plotWindow = new ImageWindow(plotImage);
        // Make master panel component to hold all subpanels
        JPanel panAll = new JPanel(); //copying CSI Spectrum Analyzer

        // Make 1 panel for Checkbox
        Panel panel1 = new Panel();
        panel1.setLayout(new GridBagLayout()); //make gridbag layout for flexible placement
        GridBagConstraints gbc = new GridBagConstraints(); //set up constraints for gridbag
        panel1.setLayout(new FlowLayout(FlowLayout.RIGHT));
        /** Add checkbox for windowing FFT*/
        gbc.gridx = 1;
        gbc.gridy = 0;
        chkFilter = new JCheckBox("Window FFT", true);
        chkFilter.addChangeListener(this);
        chkFilter.setVisible(true);
        panel1.add(chkFilter, gbc);

        /** Add button for iFFT*/
        // Make 1 panel for do iFFT and do FFT
        Panel panel2 = new Panel();
        panel2.setLayout(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.gridx = 5;
        gbc.gridy = 0;
        gbc.gridwidth = 50;
        butIFFT = new JButton("Make iFFT");
        butIFFT.addActionListener(this);
        butIFFT.setVisible(true);
        //panel1.add(butIFFT, gbc);
        panel2.add(butIFFT, gbc);
        gbc = new GridBagConstraints();
        gbc.gridx = 55;
        gbc.gridy = 0;
        gbc.gridwidth = 50;
        butFFT = new JButton("Make FFT");
        butFFT.addActionListener(this);
        butFFT.setVisible(true);
        panel2.add(butFFT, gbc);

        panAll.setLayout(new BoxLayout(panAll, BoxLayout.X_AXIS));
        panAll.add(panel1);
        panAll.add(panel2);
        plotWindow.add(panAll);
        //plotWindow.pack();

        //plotImage.getWindow().add(panel1);
        IJ.run("Out [-]");
        IJ.run("In [+]");

        //plotImage.setProperty("FHT",fft);
        plotImage.setProperty("Info","tee hee\n "+String.valueOf(imp.getBitDepth())+"\n");
        plotImage.setProperty("bitdepth",imp.getBitDepth());
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
        //createListeners(plotImage);
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
        // if selection is deleted, default selection is entire image
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

        // NEED TO EDIT: fix auto contrasting for stack
        //can try getting brightness for whole image rather than roi, using that as trigger

        /** Ariana note: This was original semi-working version. 2024/02/22*/


        if (imp == this.plotImage ) {
            if (this.img_size_x == imp.getHeight() && !sliceChanged) {
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

        if (imp_close == this.imp) {
            removeListeners(plotImage);
            closePlotImage(plotImage);
        }
        if (imp_close == this.plotImage) {
            removeListeners(imp);
        }

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
            fft.originalBitDepth = imp.getBitDepth();
            IJ.showProgress(0.0);
            fft.setShowProgress(false);

            plotImage.setProcessor(fft.getPowerSpectrum());
            plotImage.setProperty("FHT",fft);
            plotImage.setProperty("Info","tee hee\n "+String.valueOf(imp.getBitDepth())+"\n");
            plotImage.setProperty("bitdepth",imp.getBitDepth());
            plotImage.show();

            plotImage.setDisplayRange(this.img_min, this.img_max);
            plotImage.setLut(this.img_lut);
            plotImage.setProperty("Info","tee hee\n "+String.valueOf(imp.getBitDepth())+"\n");
            plotImage.setProperty("bitdepth",imp.getBitDepth());

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
    }

    /** Create image listeners*/
    private void createListeners(ImagePlus imp_listen) {
        ImageWindow win = imp_listen.getWindow();
        ImageCanvas canvas = win.getCanvas();
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addKeyListener(this);
        imp_listen.addImageListener(this);
        //plotImage.addImageListener(this);

    }

    /** Destroy image listeners*/
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

    /** Make FFT Hamming window (unused for now) */
    FloatProcessor makeRectangularHammingWindow() {

        Roi roi = imp.getRoi(); //change in getFFT()

        /* Calculate size */
        int maxN = Math.max(roi.getBounds().width, roi.getBounds().height); //change in getFFT()
        /* Find max power of 2 of size */
        int pix = (int) Math.pow(2,Math.ceil((Math.log(maxN) / Math.log(2)))); //change in getFFT()

        FloatProcessor filter_padded = new FloatProcessor(pix,pix);

        int rwidth = roi.getBounds().width;
        int rheight = roi.getBounds().height;

        //float[][] filter = new float[rwidth][rheight]; //<-- create empty filter
        FloatProcessor filter = new FloatProcessor(rwidth, rheight);
        float filt_w = 0;
        float filt_h = 0;

        int count_w = 0;
        int count_h = 0;
        //float cutoff = 0.8F;

        /** Reframe coordinates as distance to center*/
        int start_w = (int) -Math.floor((double) rwidth/2);
        int end_w = rwidth + start_w;

        int start_h = (int) -Math.floor((double) rheight/2);
        int end_h = rheight + start_h;


        /** Pad sides of image with zeros to make it power of 2 size*/
        int pad_w_left = (int) pix/2 + start_w;
        //int pad_w_right = (int) pix - pad_w_left - rwidth;
        int pad_h_left = (int) pix/2 + start_h;
        //int pad_h_right = (int) pix - pad_h_left - rheight;
        float pix_distance;

        for (int n1=start_w; n1<end_w; n1++) {

            //Calculate width hamming function
            filt_w = (float) (0.54 + 0.46 * Math.cos(Math.PI * n1 / (start_w)));


            for (int n2=start_h; n2<end_h; n2++) {

                //Calculate height hamming function
                filt_h = (float) (0.54 + 0.46 * Math.cos(Math.PI * n2 / (start_h)));

                filter_padded.putPixelValue(pad_w_left+count_w, pad_h_left+count_h, filt_w*filt_h);
                filter.putPixelValue(count_w, count_h, filt_w*filt_h);

                //Insert filter pixel into filter
                //filter_padded.putPixelValue(pad_w_left+count_w, pad_h_left+count_h, filt_w*filt_h);
                //filter.putPixelValue(count_w, count_h, filt_w*filt_h);

                count_h += 1;
            }
            count_h = 0;
            count_w +=1;
        }

        //return filter_padded;
        return filter;
    }

    /** Make FFT Hanning window */
    FloatProcessor makeRectangularHanningWindow() {

        Roi roi = imp.getRoi(); //change in getFFT()

        int rwidth = roi.getBounds().width;
        int rheight = roi.getBounds().height;

        //float[][] filter = new float[rwidth][rheight]; //<-- create empty filter
        FloatProcessor filter = new FloatProcessor(rwidth, rheight);
        float filt_r = 0;
        double pix_dist;


        for (int n1=0; n1<rwidth; n1++) {

            for (int n2=0; n2<rheight; n2++) {

                //Calculate distance from center:
                pix_dist = Math.sqrt(Math.pow((double) (2 * n1) /rwidth-1,2)+Math.pow((double) (2 * n2) /rheight-1,2));
                filt_r = (float) (0.5*(Math.cos(Math.PI*pix_dist)+1));

                if (pix_dist >=0 && pix_dist < 1)
                    filter.putPixelValue(n1, n2, filt_r);


            }
        }

        return filter;
    }

    /** Pad image with zeros to be power of 2 size*/
    FloatProcessor padZeros(FloatProcessor filteredImage) {

        int maxN = Math.max(filteredImage.getWidth(), filteredImage.getHeight());
        /** Calculate power of 2 size */
        int pix = (int) Math.pow(2,Math.ceil((Math.log(maxN) / Math.log(2))));
        /** Fit image into power of 2 size (from FFT_Filter.java) */
        int start_w = (int) -Math.floor((double) filteredImage.getWidth()/2);
        int start_h = (int) -Math.floor((double) filteredImage.getHeight()/2);
        int pad_w_left = (int) pix/2 + start_w;
        int pad_h_left = (int) pix/2 + start_h;

        //Rectangle fitRect = new Rectangle();
        //fitRect.x = (int) Math.round((pix - roiRect.getBounds().width)/2.0);
        //fitRect.y = (int) Math.round((pix - roiRect.getBounds().height)/2.0);
        //fitRect.width = roiRect.getBounds().width;
        //fitRect.height = roiRect.getBounds().height;
        /** Pad image to power of 2 size */
        FloatProcessor filteredPadImage = new FloatProcessor(pix, pix);

        filteredPadImage.insert(filteredImage,pad_w_left,pad_h_left);

        return filteredPadImage;

        //tileZeros(imp_copy.getProcessor(), pix, pix, fitRect.x, fitRect.y);
    }

    /** Pad image with a non-zero value (may be used with Hamming window) */
    FloatProcessor padNonzeros(FloatProcessor filteredImage, double padValue) {
        /** For padding the image with a nonzero background */

        int maxN = Math.max(filteredImage.getWidth(), filteredImage.getHeight());
        /** Calculate power of 2 size */
        int pix = (int) Math.pow(2,Math.ceil((Math.log(maxN) / Math.log(2))));
        /** Fit image into power of 2 size (from FFT_Filter.java) */
        int start_w = (int) -Math.floor((double) filteredImage.getWidth()/2);
        int start_h = (int) -Math.floor((double) filteredImage.getHeight()/2);
        int pad_w_left = (int) pix/2 + start_w;
        int pad_h_left = (int) pix/2 + start_h;

        //Rectangle fitRect = new Rectangle();
        //fitRect.x = (int) Math.round((pix - roiRect.getBounds().width)/2.0);
        //fitRect.y = (int) Math.round((pix - roiRect.getBounds().height)/2.0);
        //fitRect.width = roiRect.getBounds().width;
        //fitRect.height = roiRect.getBounds().height;
        /** Pad image to power of 2 size */
        FloatProcessor filteredPadImage = new FloatProcessor(pix, pix);
        filteredPadImage.add(padValue);

        filteredPadImage.insert(filteredImage,pad_w_left,pad_h_left);

        return filteredPadImage;

        //tileZeros(imp_copy.getProcessor(), pix, pix, fitRect.x, fitRect.y);
    }

    /** Multiply image with filter*/
    FloatProcessor multiplyImages(FloatProcessor ip_copy, FloatProcessor filter) {

        int w2 = ip_copy.getWidth();
        int h2 = ip_copy.getHeight();
        double val;

        for (int i = 0; i < w2; i++) {
            for (int j = 0; j < h2; j++) {
                val = ip_copy.getPixelValue(i, j)*filter.getPixelValue(i, j);
                ip_copy.putPixelValue(i, j, val);
            }
        }

        return ip_copy;
    }

    /** get a profile, analyze it and return a plot (or null if not possible) */
    FHT getFFT() {
        ImageProcessor ip = imp.getProcessor(); //evaluate if i need this
        Roi roi = imp.getRoi();
        //System.out.println("getFFT roi: " + roi);

        /** Get filter: */
        FloatProcessor filter;
        /** Copy ROI*/
        this.imp_copy = new ImagePlus(imp.getTitle(), imp.getProcessor());
        imp_copy.setRoi(roi);
        imp_copy = imp_copy.crop();
        this.ip_copy = imp_copy.getProcessor().convertToFloatProcessor(); //new 1.9
        //ip_copy = ip_copy.crop().convertToFloatProcessor(); //new 1.9
        /** Tile image roi*/
        //tileImage(); //sets up cropped ip with power of 2  //delete 1.9
        /** Multiply image roi with filter*/
        if (doFilter) {
            ////filter = makeHammingWindow();
            //filter = makeRectangularHammingWindow();
            filter = makeRectangularHanningWindow();
            //this.imp_copy.setProcessor(filter);
            //imp_copy.show();
            this.ip_copy = multiplyImages(this.ip_copy, filter);
            ////this.imp_copy.setProcessor(ip_copy);
            ////imp_copy.show();

            ////this.imp_copy = new ImagePlus(imp.getTitle(), multiplyImages(this.ip_copy, filter));
            this.ip_copy = padNonzeros(this.ip_copy, 0.54);
            //this.ip_copy = padZeros(this.ip_copy);
        }
        else {
            //this.imp_copy = new ImagePlus(imp.getTitle(), padZeros(this.ip_copy));
            this.ip_copy = padZeros(this.ip_copy);
        }
        /** Take FFT and return*/

        return new FHT(ip_copy);

    }

    /** Take the FFT of a stack */
    ImageStack getFFTStack() {
        Roi roi = imp.getRoi();
        FHT fft;

        ImageStack imp_fft_stack = new ImageStack();

        for (int i = 1; i <= imp.getNSlices(); i++) {
            this.imp.setSliceWithoutUpdate(i);
            String slice_title = "FFT of " + imp.getShortTitle();
            fft = getFFT();
            fft.transform();
            imp_fft_stack.addSlice(slice_title, fft.getPowerSpectrum());
        }

        return imp_fft_stack;
    }

    /** Take the inverse FFT of a stack*/
    ImageStack getIFFTStack() {

        /** Get the ROI mask for the inverse FFT (Fourier Filtering)*/
        Roi roi_fft = plotImage.getRoi();
        if (roi_fft == null || roi_fft.getBounds().width == 0 || roi_fft.getBounds().height == 0) {
            roi_fft = new Roi(0,0,plotImage.getWidth(), plotImage.getHeight());
            plotImage.setRoi(roi_fft);
        }
        /** Get the original ROI on the real-space image*/
        Roi roi_real = imp.getRoi();
        int start_w = (int) Math.ceil((double) (roi_fft.getBounds().width - roi_real.getBounds().width)/2);
        int start_h = (int) Math.ceil((double) (roi_fft.getBounds().height -roi_real.getBounds().height)/2);
        roi_real = new Roi(start_w, start_h,roi_real.getBounds().width, roi_real.getBounds().height );

        //ImageStack imp_ifft_stack = new ImageStack(roi_real.getBounds().width, roi_real.getBounds().height);
        ImageStack imp_ifft_stack = new ImageStack(); //Image stack for FFT
        ImageProcessor filter;
        //ImageProcessor fft_ip;
        ImagePlus fft_imp = new ImagePlus();
        String slice_title;
        ImagePlus tmp = new ImagePlus();

        for (int i=1; i <= imp.getNSlices(); i++) {
            this.imp.setSliceWithoutUpdate(i); //set slice in original image
            slice_title = "iFFT of " + imp.getShortTitle(); //set slice title
            //Take FFT
            fft_imp = FFT.forward(imp.crop());
            System.out.println(fft_imp.getPropsInfo());
            //fft_imp.setProperty("FHT",fft_imp);
            fft_imp.setRoi(roi_fft);
            IJ.run(fft_imp, "Clear Outside", "");
            //fft_imp = WindowManager.getCurrentImage();
            //fft_imp.getProcessor().fillOutside(roi_fft);
            //fft_imp.hide();

            //Multiply filter to FFT
            fft_imp = FFT.inverse(fft_imp);

            //fft_slice.inverseTransform(); //take iFFT
            //fft_slice.setRoi(roi_real); //set original ROI
            imp_ifft_stack.addSlice(slice_title, fft_imp.duplicate().getProcessor());
        }

        fft_imp.close();
        return imp_ifft_stack;

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

    public void stateChanged(ChangeEvent e) {
        if (e.getSource()==chkFilter) {
            doFilter = chkFilter.isSelected();
            FHT fft = getFFT();
            fft.transform();
            fft.originalBitDepth = imp.getBitDepth();
            IJ.showProgress(0.0);
            fft.setShowProgress(false);

            plotImage.setProcessor(fft.getPowerSpectrum());
            plotImage.setProperty("FHT",fft);
            plotImage.setProperty("Info","tee hee\n "+String.valueOf(imp.getBitDepth())+"\n");
            plotImage.setProperty("bitdepth",imp.getBitDepth());
            plotImage.show();

            plotImage.setDisplayRange(this.img_min, this.img_max);
            plotImage.setLut(this.img_lut);
            plotImage.setProperty("Info","tee hee\n "+String.valueOf(imp.getBitDepth())+"\n");
            plotImage.setProperty("bitdepth",imp.getBitDepth());
        }


    }

    @Override //no idea what Override does
    /** Checks if buttons are clicked*/
    public void actionPerformed(ActionEvent e) {

        // YOU ARE HERE Jan 29 2024
        Object b = e.getSource(); //Find source of action performed (which button)
        if (b== butIFFT) {
            //Create new window for iFFT

            //Make iFFT Stack
            ImagePlus ifftStack = new ImagePlus();
            ifftStack.setTitle("iFFT of Stack: "+imp.getShortTitle());
            ifftStack.setStack(getIFFTStack());
            ifftStack.show();

        }
        if (b== butFFT) {
            //doStack_ifft = !doStack_ifft; //Flip the state to a different switch
            ImagePlus fftStack = new ImagePlus();
            fftStack.setTitle("FFT of Stack: "+imp.getShortTitle());
            fftStack.setStack(getFFTStack()); //haven't written yet.
            fftStack.show();

        }
        if (b==chkFilter) {
            doFilter = chkFilter.isSelected();


        }


    }
}
