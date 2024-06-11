import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Menus;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Color;
import java.awt.Event;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;


/**
 * @author William Hammond
 *
 */
public class Crystal_Map implements PlugIn{
	
	//------------ global variables -------------
	
	//flags
	boolean displayRegions = false;
	boolean displayBoundaries = false;
	boolean filterMode = true;
	ButtonGroup modeGroup, toolGroup;
	ButtonModel filterModel, peakModel;
	
	//filter and peak finding parameters
	FloatProcessor rawData;
	FloatProcessor rawPlusBoundaries;
	ImageStack stack;
	float highestPeak = 0.0f;
	int width = 0, height = 0;
	int xCrystals = 0, yCrystals = 0;
	int peakSize = 13;
	int[][] localPeakSize;
	int largestPeakSize = 0;
	int threshold = 0;
	int peakCutoff = 0;
	
	//variables for handling peaks
	Vector listPoints = new Vector(0, 16);
	int numPoints = 0;
	final Color color = Color.yellow;
	
	//peak mapping, voronoi
	PeakMapper mapper;
	ImagePlus zoneImage;
	double rowHeight[];
	float[][] zones;
	
	//tools
	int currentTool = 0;
	int tools = 6;
	ButtonModel[] toolModel = new ButtonModel[tools];
	final int ADD_PEAK = 0;
	final int ADD_LOCAL_PEAK = 1;
	final int MOVE_PEAK = 2;
	final int REMOVE_PEAK = 3;
	final int ZOOM = 4;
	final int CORRECT = 5;
	JProgressBar progress;
	
	//class instances
	PointHandler ph;
	PointAction pa;
	ImagePlus imp;
	
	
	
	public void run(String arg){
		
		
		IJ.runPlugIn("Open_Kmax", "");
		imp = WindowManager.getCurrentImage();
		
		if (imp == null) {
			IJ.noImage();
			return;
		}
		((FloatProcessor)imp.getProcessor()).resetMinAndMax();
		imp.updateAndDraw();
		width = imp.getProcessor().getWidth();
		height = imp.getProcessor().getHeight();
		localPeakSize = new int[height][width];
		
		mapper = new PeakMapper();
		
		final ImageCanvas ic = imp.getWindow().getCanvas();
		ph = new PointHandler(imp);
		pa = new PointAction(imp, ph);
		ph.setPointAction(pa);
		
		rawData = (FloatProcessor)imp.getProcessor();
		stack = imp.createEmptyStack();
		
		highestPeak = (float)rawData.getMax();
		rawPlusBoundaries = (FloatProcessor)rawData.duplicate();

		//delegate the gui to the event-dispatching thread:
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				new CrystalMapper(imp, ic, ph);
			}
		});
	}
	
	
	class CrystalCanvas extends ImageCanvas {
		
		public CrystalCanvas(ImagePlus imp) {
			super(imp);
		}
		
		public void paint(Graphics g) {
			super.paint(g);
			int size = 40;
			int screenSize = (int)(size*getMagnification());
			int x = screenX(imageWidth/2 - size/2);
			int y = screenY(imageHeight/2 - size/2);
			g.setColor(Color.red);
			g.drawOval(x, y, screenSize, screenSize);
		}
		
		public void mousePressed(MouseEvent e) {
			super.mousePressed(e);
			IJ.write("mousePressed: ("+offScreenX(e.getX())+","+offScreenY(e.getY())+")");
		}
	}
	
	
	class CrystalMapper extends ImageWindow implements 	ActionListener, 
														ChangeListener, 
														MouseMotionListener	{
		
		private PointHandler ph;
		private int steps = 5, peakButtons = 7, modes = 2, sliders = 3;
		private Button[] stepButton = new Button[steps];
		private Button[] peakButton = new Button[peakButtons];
		private JToggleButton[] toolButton = new JToggleButton[tools];
		private JToggleButton[] modeButton = new JToggleButton[modes];
		private Icon[] toolIcon = new Icon[tools];
		private JSlider[] slider = new JSlider[sliders];
		private String[] stepButtonName = {"1. Filter", "2. Find Peaks", "3. Map Peaks", "4. Define Regions", "5. Save for Kmax"};
		private String[] peakButtonName = {"Remove All", "Count All", "Show Crystal #", "Show Boundaries", "View Peaks", "Open Peaks", "Save Peaks"};
		private String[] modeName = {"Filter Mode", "Peak Mode"};
		private String[] toolName = {"Add Peak", "Add Local Peak", "Move Peak", "Remove Peak", "Zoom", "Correct"};
		private String[] sliderName = {"Peak Size", "Threshold", "Peak Cutoff"};
		private int[] defaultSliderValue = {peakSize, threshold, peakCutoff};
		private JLabel toolLabel = new JLabel("");
		private JToolBar toolBar;
		
		public CrystalMapper(ImagePlus imp, ImageCanvas ic, PointHandler ph) {
			super(imp, ic);
			this.ph = ph;
			createGui();
		}
		
		public void createGui() {
			
//			construct toolbar
			toolBar = new JToolBar(JToolBar.VERTICAL);
			toolGroup = new ButtonGroup();
			for(int i = 0; i < tools; i++){
				toolIcon[i] = new ImageIcon("plugins/CrystalMap/" + toolName[i] + ".png", toolName[i]);
				toolButton[i] = new JToggleButton(toolIcon[i]);
				toolGroup.add(toolButton[i]);
				toolModel[i] = toolButton[i].getModel();
				toolButton[i].addActionListener(this);
				toolBar.add(toolButton[i]);
			}
			
			//parent panel attached to the left of the ImageCanvas 
			setLayout(new BorderLayout());
			Panel panel = new Panel();
			panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
			
			//top of panel - calibration steps: filter, find peaks, map peaks, find regions, save crystal map
			for(int i = 0; i < steps; i++){
				stepButton[i] = new Button(stepButtonName[i]);
				stepButton[i].addActionListener(this);
				panel.add(stepButton[i]);
			}
			stepButton[3].setEnabled(false);
			stepButton[4].setEnabled(false);

			//middle of panel - toolbar + single tool buttons wrapped in another panel
			JPanel peakPanel = new JPanel();
			peakPanel.setLayout(new FlowLayout());
			JPanel peakButtonPanel = new JPanel();
			peakButtonPanel.setLayout(new BoxLayout(peakButtonPanel, BoxLayout.Y_AXIS));
			for(int i = 0; i < peakButtons; i++){
				peakButton[i] = new Button(peakButtonName[i]);
				peakButton[i].addActionListener(this);
				peakButtonPanel.add(peakButton[i]);
			}
			peakButtonPanel.add(new JLabel(""));
			modeGroup = new ButtonGroup();
			for(int i = 0; i < modes; i++){
				modeButton[i] = new JToggleButton(modeName[i]);
				modeGroup.add(modeButton[i]);
				modeButton[i].addActionListener(this);
				peakButtonPanel.add(modeButton[i]);
			}
			filterModel = modeButton[0].getModel();
			peakModel = modeButton[1].getModel();
			modeGroup.setSelected(filterModel, true);
			peakPanel.add(peakButtonPanel);
			peakPanel.add(toolBar);
			panel.add(peakPanel);
			panel.add(toolLabel);
			
			//bottom of panel - sliders and progress bar
			for(int i = 0; i < sliders; i++){
				slider[i] = new JSlider(0,60);
				slider[i].setMajorTickSpacing(10);
				slider[i].setMinorTickSpacing(1);
				slider[i].setValue(defaultSliderValue[i]);
				slider[i].setPaintTicks(true);
				slider[i].setPaintLabels(true);
				panel.add(new JLabel(sliderName[i], JLabel.CENTER));
				panel.add(slider[i]);
				slider[i].addChangeListener(this);
			}
			progress = new JProgressBar();
			panel.add(new JLabel(""));
			panel.add(progress);
			
			//pack parent panel
			add(getCanvas(), BorderLayout.CENTER);
			add(panel, BorderLayout.WEST);
			pack();
		}
		
		
		/**
		 * determines which button was pressed and calls appropriate function
		 */
		public void actionPerformed(ActionEvent e) {
			Object b = e.getSource();
			
			if(b.getClass() == Button.class){	//a button was clicked
				String name = ((Button)b).getLabel();
				
				if(name.equals("1. Filter")){
					imp.setProcessor(null, filter(imp.getProcessor()));
					((FloatProcessor)imp.getProcessor()).resetMinAndMax();
					imp.updateAndDraw();
				}
				if(name.equals("2. Find Peaks")){
					locatePeaks((FloatProcessor)imp.getProcessor(), ph);
					modeGroup.setSelected(peakModel, true);
					imp.setRoi(ph);
				}
				if(name.equals("3. Map Peaks")){
					//get xCrystals and yCrystals
					double errorValue = 0;
					double value = IJ.getNumber("How many columns?", errorValue);
					if(value == IJ.CANCELED || value == errorValue)
						return;
					xCrystals = (int)Math.round(value);
					value = IJ.getNumber("How many rows?", errorValue);
					if(value == IJ.CANCELED || value == errorValue)
						return;
					yCrystals = (int)Math.round(value);
					rowHeight = new double[yCrystals + 1];
					//enumerate all peaks
					mapper.mapPeaks(null,0);
					displayRegions = true;
					peakButton[2].setLabel("Hide Crystal #");
					imp.setRoi(ph);
					stepButton[3].setEnabled(true);
				}
				if(name.equals("4. Define Regions")){
					
					modeGroup.setSelected(filterModel, true);
					FloatProcessor zoneProcessor = mapper.defineRegions();
					zoneImage = new ImagePlus("Mapped Regions", zoneProcessor);
					zoneImage.show();
					zoneImage.draw();
					displayRegions = false;
					imp.setProcessor("Boundaries", rawPlusBoundaries);
					imp.updateAndDraw();
					peakButton[2].setLabel("Show Crystal #");
					peakButton[3].setLabel("Hide Boundaries");
					stepButton[4].setEnabled(true);
					//	ImageStack stack = imp.getStack();
					//	stack.addSlice("Crystal Map", zones);
					//	imp.setSlice(1);
					
					//	(zoneImage.getProcessor()).resetMinAndMax();
					//	zoneImage.updateAndDraw();
					//	imp.setProcessor("Mapped Regions", zones);
					//	imp.updateAndDraw();
				}
				if(name.equals("5. Save for Kmax")){
					WindowManager.setCurrentWindow(zoneImage.getWindow());
					IJ.runPlugIn("Save_Kmax", "");
				}
				if(name.equals("Remove All")){
					ph.removePoints();
				}
				if(name.equals("Count All")){
					IJ.showMessage(Integer.toString(listPoints.size()) + " total peaks.");
				}
				if(name.equals("Show Crystal #")){
					((Button)b).setLabel("Hide Crystal #");
					displayRegions = true;
					imp.setRoi(ph);
				}
				if(name.equals("Hide Crystal #")){
					((Button)b).setLabel("Show Crystal #");
					displayRegions = false;
					imp.setRoi(ph);
				}
				if(name.equals("Show Boundaries")){
					((Button)b).setLabel("Hide Boundaries");
				//	displayBoundaries = true;
					imp.setProcessor("Boundaries", rawPlusBoundaries);
					imp.updateAndDraw();
					imp.setRoi(ph);
				}
				if(name.equals("Hide Boundaries")){
					((Button)b).setLabel("Show Boundaries");
				//	displayBoundaries = false;
					imp.setProcessor("Raw image", rawData);
					imp.updateAndDraw();
					imp.setRoi(ph);
				}
				if(name.equals("View Peaks")){
					viewPeaks();
				}
				if(name.equals("Save Peaks")){
					savePeaks();
				}
				if(name.equals("Open Peaks")){
					openPeaks();
				}
			}
			
			if(b.getClass() == JToggleButton.class){//a toggle button was clicked (mode button)
				filterMode = modeButton[0].isSelected();	//filter mode button updates boolean field
				if(filterMode){
					pa.cleanUpListeners();
				}	
				else{
					pa.installListeners();
					imp.setRoi(ph);
				}
				//update currentTool
				ButtonModel temp = toolGroup.getSelection();
				for(int i = 0; i < tools; i++){
					if(temp == toolModel[i])
						currentTool = i;
				}
			}
		}
		
		public void mouseMoved(MouseEvent e){
			Object b = e.getSource();
			for(int i = 0; i < tools; i++){
				if(b == toolButton[i])
					toolLabel.setText(toolName[i]);
			}
		}
		public void mouseDragged(MouseEvent e){
		}
		
		/**
		 * called when a slider is changed; updates variables to reflect current slider positions
		 */
		public void stateChanged(ChangeEvent e){
			peakSize = slider[0].getValue();
			threshold = slider[1].getValue();
			peakCutoff = slider[2].getValue();
		}
		
		/**
		 * filter entire image with a sine convolutative filter
		 * @param roi
		 * @return
		 */
		public FloatProcessor filter(ImageProcessor currentProcessor) {
			int x, y, i, j;
			float temp = 0.0f;
			double d;
			int l;
			double normal, sum;
			FloatProcessor tempData = new FloatProcessor(width, height);
		//	FloatProcessor tempData = (FloatProcessor)currentProcessor;
		//	float[] pixels = (float[])tempData.getPixels();
			IJ.showStatus("Filtering . . .");
			Rectangle bounds = imp.getRoi().getBounds();
			
			//Find radius of filter
			l = (peakSize - 1) / 2;
			
			double[][] sinxFilter = new double[l * 2 + 1][l * 2 + 1];
			
			normal = (threshold / 100.0);
			highestPeak = 0;
			sum = 0;
			
			//Build filter based on rotation of the function sin(x)/x	
			
			for (i = -l; i <= l; i++) {
				for (j = -l; j <= l; j++) {
					d = Math.sqrt(i*i + j*j);		
					if (d == 0) {
						sinxFilter[i + l][j + l] = 1;
						sum +=  1;
					}
					else {
						sinxFilter[i + l][j + l] = (Math.sin(Math.PI * d / l) / (Math.PI * d / l));
						sum += sinxFilter[i + l][j + l];
					}
				}
			}
			
			//Normalize so that flat fields will produce slightly less than zero
			sum = sum / ((l * 2 + 1) * (l * 2 + 1));
			normal = sum * (normal + 1);		//Drop curve a further x% lower
			
			for (i = -l; i <= l; i++) {
				for (j = -l; j <= l; j++) {
					sinxFilter[i + l][j + l] -= normal;
				}
			}
			
			//copy pixel values outside filter area (should be easier to overwrite current image only in area of filter, but 
			//calling putPixelValue on the current image results in NaN values - why?
			for(x = 0; x < width; x++){
				for(y = 0; y < height; y++){
					tempData.putPixelValue(x, y, currentProcessor.getPixelValue(x, y));
				}
			}
			if(peakSize > largestPeakSize)
				largestPeakSize = peakSize;
			
			//Pass filter over selected area
		//	double progress = 0.0;
		//	progress.setMaximum(cursors[1]-1);
		//	IJ.showProgress(0.0);
			for (x = bounds.x + l; x <= bounds.x + bounds.width - l; x++) {
				
			//	if(x%100 == 0){
				//	System.out.println(x);
				//	IJ.showProgress((double)x / (double)cursors[1]);
			//	progress.setValue(x);
				//}
					
				
				for (y = bounds.y + l; y <= bounds.y + bounds.height - l; y++) {
				//	currentProcessor.putPixelValue(x, y, 0.0);
					localPeakSize[y][x] = peakSize;
					temp = 0.0f;
					for (i = 0; i < 2*l+1; i++)
						for (j = 0; j < 2*l+1; j++){
							temp += rawData.getPixelValue(x + i - l, y + j - l) * sinxFilter[i][j];
						}
					if (temp < 0.0){
						temp = 0.0f;
					}
					tempData.putPixelValue(x, y, temp);
				}
			}
	//		IJ.showProgress(1.0);
			IJ.showStatus("Filtering done. Ready to find peaks.");
			return tempData;
		}
		
		
		/**
		 * Locate peaks within selected roi; if no roi selected, locate peaks over entire image
		 * either way, first clear peaks already within search bounds
		 * @param data
		 * @param cutoff
		 * @param radius
		 * @return
		 */
		public void locatePeaks(FloatProcessor ip, PointHandler ph){
			
			int x, y, tempX, tempY, status = 0, peaks = 0;
			float center, compare;
			int size = 5;
			int r2;// = size*size;
			int yMax = 0;
			int[] cursors = new int[4];
	//		Rectangle bounds = ip.getRoi();
			

			//search roi for peaks
			for(x = largestPeakSize; x < width - largestPeakSize; x++){
				for(y = largestPeakSize; y < height - largestPeakSize; y++){
					size = localPeakSize[y][x] / 2;
					if(size != 0){	//only search for peaks in areas that have been filtered
						status = 0;
						center = ip.getPixelValue(x,y);
						r2 = size*size;
						for (tempX = x - size; tempX <= x + size; tempX++){
							yMax = (int)(Math.sqrt(r2 - (x-tempX)*(x-tempX)) + 0.5);
							for (tempY = y - yMax; tempY <= y + yMax; tempY++){
								compare = ip.getPixelValue(tempX, tempY);
								if ((center <= compare) && !((tempX == x) && (tempY == y)))
									status += 1;
							}
						}
						//If a local maxima is found and is higher than the cutoff value (i.e. not noise), then add it to the peaks list
						if ((status < 1) && (center >= highestPeak * peakCutoff))
						{
							ph.addPoint(x,y);
							peaks++;
						}
					}
				}
			}
		}
		
				
		public int[][] processorToArray(FloatProcessor inData){
			int[][] outData = new int[inData.getHeight()][inData.getWidth()];
			for(int j = 0; j < inData.getHeight(); j++){
				for(int i = 0; i < inData.getWidth(); i++){
					outData[j][i] = inData.getPixel(i,j);
				}
			}
			return outData;
		}
		
		public FloatProcessor arrayToProcessor(int[][] inData){
			FloatProcessor outData = new FloatProcessor(inData[0].length, inData.length);
			for(int j = 0; j < inData.length; j++){
				for(int i = 0; i < inData[0].length; i++){
					outData.putPixel(i, j, inData[j][i]);
				}
			}
			return outData;
		}
		
		/**
		 * View all current peaks in a list format that shows position and crystal number
		 *
		 */
		public void viewPeaks(){
				Peak p;
				String n, x, y, id;
				IJ.getTextPanel().setFont(new Font("Monospaced", Font.PLAIN, 12));
				IJ.setColumnHeadings(" point\t      x\t      y\t xtalID");
					for (int k = 0; (k < listPoints.size()); k++) {
						n = "" + k;
						while (n.length() < 6) {
							n = " " + n;
						}
						p = (Peak)listPoints.elementAt(k);
						x = "" + p.x;
						while (x.length() < 7) {
							x = " " + x;
						}
						y = "" + p.y;
						while (y.length() < 7) {
							y = " " + y;
						}
						id = "" + p.getCrystalNumber();
						while(id.length() < 7){
							id = " " + id;
						}
						IJ.write(n + "\t" + x + "\t" + y + "\t" + id);
					}
		}
		
		/**
		 * Save the current list of peaks (positions and crystal numbers) to a text file to continue calibration at another time
		 *
		 */
		public void savePeaks(){
			SaveDialog od = new SaveDialog("Save peaks as text file", imp.getTitle() + ".txt", ".txt");
			String directory = od.getDirectory();
			String name = od.getFileName();
			String path = "";
			if (name!=null) {
				path = directory+name;
				Menus.addOpenRecentItem(path);
			}    
			else
				return;
			try {
				final FileWriter fw = new FileWriter(path);
				Peak p;
				String n, x, y, id;
				fw.write("point     x     y slice color\n");
					for (int k = 0; (k < listPoints.size()); k++) {
						n = "" + k;
						while (n.length() < 5) {
							n = " " + n;
						}
						p = (Peak)listPoints.elementAt(k);
						x = "" + p.x;
						while (x.length() < 5) {
							x = " " + x;
						}
						y = "" + p.y;
						while (y.length() < 5) {
							y = " " + y;
						}
						id = "" + p.getCrystalNumber();
						while(id.length() < 5){
							id = " " + id;
						}
						fw.write(n + " " + x + " " + y + " " + id + "\n");
					}
				fw.close();
			} catch (IOException e) {
				IJ.error("IOException exception");
			}
		}
			
		/**
		 * Open a text file with previously saved peak positions and crystal numbers
		 *
		 */
		public void openPeaks(){
			OpenDialog od = new OpenDialog("Choose a Peak text file:", "");
			String directory = od.getDirectory();
			String name = od.getFileName();
			String path = "";
			if (name!=null) {
				path = directory+name;
				Menus.addOpenRecentItem(path);
			}    
			else
				return;
			try {
				final FileReader fr = new FileReader(path);
				final BufferedReader br = new BufferedReader(fr);
				ph.removePoints();
				String line;
				String pString, xString, yString, idString;
				int separatorIndex;
				int x, y, id;
				if ((line = br.readLine()) == null) {
					fr.close();
					return;
				}
				while ((line = br.readLine()) != null) {
					line = line.trim();
					separatorIndex = line.indexOf(' ');
					if (separatorIndex == -1) {
						fr.close();
						IJ.error("Invalid file");
						return;
					}
					line = line.substring(separatorIndex);
					line = line.trim();
					separatorIndex = line.indexOf(' ');
					if (separatorIndex == -1) {
						fr.close();
						IJ.error("Invalid file");
						return;
					}
					xString = line.substring(0, separatorIndex);
					xString = xString.trim();
					line = line.substring(separatorIndex);
					line = line.trim();
					separatorIndex = line.indexOf(' ');
					if (separatorIndex == -1) {
						separatorIndex = line.length();
					}
					yString = line.substring(0, separatorIndex);
					yString = yString.trim();
					line = line.substring(separatorIndex);
					line = line.trim();
					separatorIndex = line.indexOf(' ');
					if (separatorIndex == -1) {
						separatorIndex = line.length();
					}
					idString = line.substring(0, separatorIndex);
					idString = idString.trim();
					x = Integer.parseInt(xString);
					y = Integer.parseInt(yString);
					id = Integer.parseInt(idString);
					ph.addPoint(x, y, id);
				}
				fr.close();
			} catch (FileNotFoundException e) {
				IJ.error("File not found exception");
			} catch (IOException e) {
				IJ.error("IOException exception");
			} catch (NumberFormatException e) {
				IJ.error("Number format exception");
			}
			imp.setRoi(ph);
		}
	}
	

	public class PointAction extends ImageCanvas implements FocusListener, KeyListener, MouseListener, MouseMotionListener{
			
		private ImagePlus imp;
		private PointHandler ph;
	//	private PointToolbar tb;
		private boolean active = false;
		private boolean keyPressed = false;
		private int currentKey = 0;
		private KeyEvent lastKeyEvent;
		
		
		
		/*********************************************************************
		 Listen to <code>focusGained</code> events.
		 @param e Ignored.
		 ********************************************************************/
		public void focusGained (final FocusEvent e) {
			active = true;
			if(!filterMode)
				imp.setRoi(ph);
		}
		
		
		/*********************************************************************
		 Listen to <code>focusGained</code> events.
		 @param e Ignored.
		 ********************************************************************/
		public void focusLost (final FocusEvent e) {
			active = false;
			if(!filterMode)
				imp.setRoi(ph);
		} 
		
		
		/*********************************************************************
		 Return true if the window is active.
		 ********************************************************************/
		public boolean isActive () {
			return(active);
		} 
		
		
		public void keyPressed (final KeyEvent e) {
			if(!filterMode){
				keyPressed = true;
				active = true;
				lastKeyEvent = e;
				switch (e.getKeyCode()) {
				case KeyEvent.VK_COMMA:
					if (1 < imp.getCurrentSlice()) {
						imp.setSlice(imp.getCurrentSlice() - 1);
						imp.setRoi(ph);
						updateStatus();
					}
					return;
				case KeyEvent.VK_PERIOD:
					if (imp.getCurrentSlice() < imp.getStackSize()) {
						imp.setSlice(imp.getCurrentSlice() + 1);
						imp.setRoi(ph);
						updateStatus();
					}
					return;
				}
				final Peak p = ph.getPoint();
				if (p == null) {
					return;
				}
				final int x = p.x;
				final int y = p.y;
				int scaledX;
				int scaledY;
				int scaledShiftedX;
				int scaledShiftedY;
				switch (e.getKeyCode()) {
				case KeyEvent.VK_DELETE:
				case KeyEvent.VK_BACK_SPACE:
					ph.removePoint();
					break;
				case KeyEvent.VK_DOWN:
					scaledX = imp.getWindow().getCanvas().screenX(x);
					scaledShiftedY = imp.getWindow().getCanvas().screenY(y
							+ (int)Math.ceil(1.0 / imp.getWindow().getCanvas().getMagnification()));
					ph.movePoint(scaledX, scaledShiftedY);
					break;
				case KeyEvent.VK_LEFT:
					scaledShiftedX = imp.getWindow().getCanvas().screenX(x
							- (int)Math.ceil(1.0 / imp.getWindow().getCanvas().getMagnification()));
					scaledY = imp.getWindow().getCanvas().screenY(y);
					ph.movePoint(scaledShiftedX, scaledY);
					break;
				case KeyEvent.VK_RIGHT:
					scaledShiftedX = imp.getWindow().getCanvas().screenX(x
							+ (int)Math.ceil(1.0 / imp.getWindow().getCanvas().getMagnification()));
					scaledY = imp.getWindow().getCanvas().screenY(y);
					ph.movePoint(scaledShiftedX, scaledY);
					break;
				case KeyEvent.VK_TAB:
					ph.nextPoint();
					break;
				case KeyEvent.VK_SPACE:
					break;
				case KeyEvent.VK_UP:
					scaledX = imp.getWindow().getCanvas().screenX(x);
					scaledShiftedY = imp.getWindow().getCanvas().screenY(y
							- (int)Math.ceil(1.0 / imp.getWindow().getCanvas().getMagnification()));
					ph.movePoint(scaledX, scaledShiftedY);
				}
				imp.setRoi(ph);
				updateStatus();
			}
		} 
		
		
		public void keyReleased (final KeyEvent e) {
			active = true;
			keyPressed = false;
		} 
		
		
		public void keyTyped (final KeyEvent e) {
			active = true;
		} 
		
		public void mouseClicked (final MouseEvent e) {
			active = true;
		} 
		
	
		public void mouseDragged (final MouseEvent e) {
			active = true;
			if(filterMode)
				super.mouseDragged(e);
			else{
				final int x = e.getX();
				final int y = e.getY();
		//		if (tb.getCurrentTool() == MOVE_PEAK) {
				if(currentTool == MOVE_PEAK){
					ph.movePoint(x, y);
					imp.setRoi(ph);
				}
				mouseMoved(e);
			}
		} 
		
		/*********************************************************************
		 Listen to <code>mouseEntered</code> events.
		 @param e Ignored.
		 ********************************************************************/
		public void mouseEntered (final MouseEvent e) {
			active = true;
		} 
		
		
		/*********************************************************************
		 Listen to <code>mouseExited</code> events. Clear the ImageJ status
		 bar.
		 @param e Event.
		 ********************************************************************/
		public void mouseExited (final MouseEvent e) {
			active = false;
			IJ.showStatus("");
		} 
		
		
		/*********************************************************************
		 Listen to <code>mouseMoved</code> events. Update the ImageJ status
		 bar.
		 @param e Event.
		 ********************************************************************/
		public void mouseMoved (final MouseEvent e) {
			if(filterMode)
				super.mouseMoved(e);
			else{
				active = true;
				setControl();
				final int x = imp.getWindow().getCanvas().offScreenX(e.getX());
				final int y = imp.getWindow().getCanvas().offScreenY(e.getY());
				IJ.showStatus(imp.getLocationAsString(x, y) + getValueAsString(x, y));
			}
		} 
		
		
		/*********************************************************************
		 Listen to <code>mousePressed</code> events. Perform the relevant
		 action.
		 @param e Event.
		 ********************************************************************/
		public void mousePressed (final MouseEvent e) {
			active = true;
			if(filterMode)
				super.mousePressed(e);
			else{
				final int x = e.getX();
				final int y = e.getY();
				
				if(!keyPressed){
					switch (currentTool) {
					case ADD_PEAK:
						ph.addPoint(
								imp.getWindow().getCanvas().offScreenX(x),
								imp.getWindow().getCanvas().offScreenY(y));
						break;
					case ZOOM:
						final int flags = e.getModifiers();
						if ((flags & (Event.ALT_MASK | Event.META_MASK | Event.CTRL_MASK)) != 0) {
							imp.getWindow().getCanvas().zoomOut(x, y);
						}
						else {
							imp.getWindow().getCanvas().zoomIn(x, y);
						}
						break;
					case MOVE_PEAK:
						ph.findClosest(x, y);
						break;
					case REMOVE_PEAK:
						ph.findClosest(x, y);
						ph.removePoint();
						break;
					case ADD_LOCAL_PEAK:
						ph.addLocalPeak(
								imp.getWindow().getCanvas().offScreenX(x),
								imp.getWindow().getCanvas().offScreenY(y));
						break;
					case CORRECT:
						ph.findClosest(x,y);
						Peak badPeak = ph.getPoint();
					//	ph.removePoint();
						double badID = badPeak.getCrystalNumber();
						double newID = IJ.getNumber("Enter correct crystal number:", badID);
						if(newID != IJ.CANCELED && newID != badID){	//user entered a new, valid number
							mapper.mapPeaks(badPeak, (int)Math.round(newID));
						}
					}
					
					imp.setRoi(ph);
				} 
				if(keyPressed){
					switch (lastKeyEvent.getKeyCode()) {
					case KeyEvent.VK_COMMA:
						
						return;
					case KeyEvent.VK_PERIOD:
						
						return;
					}
				}
			}
			
		}
		
		
		/*********************************************************************
		 Listen to <code>mouseReleased</code> events.
		 @param e Ignored.
		 ********************************************************************/
		public void mouseReleased (final MouseEvent e) {
			active = true;
		} 
		
		
		/*********************************************************************
		 This constructor stores a local copy of its parameters and initializes
		 the current control.
		 @param imp <code>ImagePlus</code> object where points are being picked.
		 @param ph <code>PointHandler</code> object that handles operations.
		 ********************************************************************/
		public PointAction (final ImagePlus imp, final PointHandler ph) {
			super(imp);
			this.imp = imp;
			this.ph = ph;
		}
		
		/**
		 * Override ImageJ listeners with local functions
		 *
		 */
		public void installListeners () {
			final ImageWindow iw = imp.getWindow();
			final ImageCanvas ic = iw.getCanvas();
			iw.requestFocus();
			iw.removeKeyListener(IJ.getInstance());
			MouseListener[] ml = ic.getMouseListeners();
			for(int i = 0; i < ml.length; i++){
				ic.removeMouseListener(ml[i]);
			}
			MouseMotionListener[] mml = ic.getMouseMotionListeners();
			for(int i = 0; i < mml.length; i++){
				ic.removeMouseMotionListener(mml[i]);
			}
			ic.addMouseMotionListener(this);
			ic.addMouseListener(this);
			iw.addKeyListener(this);
		} 
		
		/**
		 * Remove local listeners, install default ImageJ listeners
		 *
		 */
		public void cleanUpListeners () {
			final ImageWindow iw = imp.getWindow();
			final ImageCanvas ic = iw.getCanvas();
			iw.removeKeyListener(this);
			MouseListener[] ml = ic.getMouseListeners();
			for(int i = 0; i < ml.length; i++){
				ic.removeMouseListener(ml[i]);
			}
			MouseMotionListener[] mml = ic.getMouseMotionListeners();
			for(int i = 0; i < mml.length; i++){
				ic.removeMouseMotionListener(mml[i]);
			}
			ic.addMouseMotionListener(ic);
			ic.addMouseListener(ic);
			iw.addKeyListener(IJ.getInstance());
		}
		
		
		private String getValueAsString (final int x, final int y) {
			final Calibration cal = imp.getCalibration();
			final int[] v = imp.getPixel(x, y);
			int type = imp.getType();
			switch (type) {
			case ImagePlus.GRAY8:
			case ImagePlus.GRAY16:
				final double cValue = cal.getCValue(v[0]);
				if (cValue==v[0]) {
					return(", value=" + v[0]);
				}
				else {
					return(", value=" + IJ.d2s(cValue) + " (" + v[0] + ")");
				}
			case ImagePlus.GRAY32:
				return(", value=" + Float.intBitsToFloat(v[0]));
			case ImagePlus.COLOR_256:
				return(", index=" + v[3] + ", value=" + v[0] + "," + v[1] + "," + v[2]);
			case ImagePlus.COLOR_RGB:
				return(", value=" + v[0] + "," + v[1] + "," + v[2]);
			default:
				return("");
			}
		} 
		
		
		private void setControl () {
			switch (currentTool) {
			case ADD_PEAK:
				imp.getWindow().getCanvas().setCursor(crosshairCursor);
				break;
			case ZOOM:
			case MOVE_PEAK:
			case REMOVE_PEAK:
				imp.getWindow().getCanvas().setCursor(defaultCursor);
				break;
			}
		} 
		
		
		private void updateStatus (
		) {
			final Peak p = ph.getPoint();
			if (p == null) {
				IJ.showStatus("");
				return;
			}
			final int x = p.x;
			final int y = p.y;
			IJ.showStatus(imp.getLocationAsString(x, y) + getValueAsString(x, y));
		} 
		
	}	
	
	
	/**
	 * 	Peak overlay to image - acts as an ImageJ ROI
	 *
	 */
	class PointHandler extends Roi{
		
		public int penRadius = 2;
		private ImagePlus imp;
		private PointAction pa;
		private int currentPoint = -1;
		private boolean started = false;
		
		
		
		/*********************************************************************
		 This method adds a new point to the list. The
		 points are stored in pixel units rather than canvas units to cope
		 for different zooming factors.
		 @param x Horizontal coordinate, in canvas units.
		 @param y Vertical coordinate, in canvas units.
		 ********************************************************************/
		public void addPoint (final int x, final int y) {
			addPoint(x,y,-1);
		} 
		public void addPoint( final int x, final int y, final int id){
			final Peak p = new Peak(x,y);
			p.setCrystalNumber(id);
			listPoints.addElement(p);
			currentPoint = numPoints;
			numPoints++;
		}
		
		/**
		 * add a peak within 
		 * @param x
		 * @param y
		 */
		public void addLocalPeak(int x, int y){

			int localMax = 0;
			int[] peak = new int[2];
			int r2 = (peakSize/2)*(peakSize/2);
			
			//Search area surrounding (x,y) for the highest peak
			//If there are 2 or more pixels at the max value, average coordinates
			for (int i = x - peakSize/2; i <= x + peakSize/2; i++) {
				yMax = (int)(Math.sqrt(r2 - (x-i)*(x-i)) + 0.5);
				for (int j = y - yMax; j <= y + yMax; j++) {
					
					if (rawData.getPixel(i, j) > localMax) {
						peak[0] = i;
						peak[1] = j;
						localMax = rawData.getPixel(i, j);
					}
					else if (rawData.getPixel(i, j) == localMax) {
						peak[0] = (peak[0] + i) / 2;
						peak[1] = (peak[1] + j) / 2;
					}	
				}
				
			}
			addPoint(peak[0], peak[1]);
			
		}
		
		
		/*********************************************************************
		 Draw the landmarks and outline the current point if there is one.
		 @param g Graphics environment.
		 ********************************************************************/
		public void draw (final Graphics g) {
			int SIZE = 2;
			if (started) {
				final float mag = (float)ic.getMagnification();
				final int dx = (int)(mag / 2.0);
				final int dy = (int)(mag / 2.0);
				for (int k = 0; (k < numPoints); k++) {
					final Peak p = (Peak)listPoints.elementAt(k);
					g.setColor(color);
			//		if(displayPoints)
						g.fillOval(ic.screenX(p.x - SIZE) + dx, ic.screenY(p.y - SIZE) + dy, (SIZE+(int)mag)*2, (SIZE+(int)mag)*2);
					if(displayRegions)
						if(p.getCrystalNumber() >= 0)
							g.drawString(Integer.toString(p.getCrystalNumber()), ic.screenX(p.x - SIZE) + dx, ic.screenY(p.y - SIZE) + dy);
				}
				if (updateFullWindow) {
					updateFullWindow = false;
					imp.draw();
				}
			}
		}
		
		/*********************************************************************
		 Let the point that is closest to the given coordinates become the
		 current landmark.
		 @param x Horizontal coordinate, in canvas units.
		 @param y Vertical coordinate, in canvas units.
		 ********************************************************************/
		public void findClosest (int x, int y) {
			if (numPoints == 0) {
				return;
			}
			x = ic.offScreenX(x);
			y = ic.offScreenY(y);
			Peak q = new Peak(x,y);
			Peak p = (Peak)listPoints.elementAt(currentPoint);
			double distance = q.distance(p);;
			for (int k = 0; (k < numPoints); k++) {
				p = (Peak)listPoints.elementAt(k);
				double candidate = q.distance(p);
				if (candidate < distance) {
					distance = candidate;
					currentPoint = k;
				}
			}
		} 
		
		
		/*********************************************************************
		 Return the current point as a <code>Point</code> object.
		 ********************************************************************/
		public Peak getPoint (
		) {
			return((0 <= currentPoint) ? ((Peak)listPoints.elementAt(currentPoint))
					: (null));
		}
		
		
		/*********************************************************************
		 Return the list of points.
		 ********************************************************************/
		public Vector getPoints (
		) {
			return(listPoints);
		} 
		
		
		/*********************************************************************
		 Modify the location of the current point. Clip the admissible range
		 to the image size.
		 @param x Desired new horizontal coordinate in canvas units.
		 @param y Desired new vertical coordinate in canvas units.
		 ********************************************************************/
		public void movePoint (int x,int y) {
			if (0 <= currentPoint) {
				x = ic.offScreenX(x);
				y = ic.offScreenY(y);
				x = (x < 0) ? (0) : (x);
				x = (imp.getWidth() <= x) ? (imp.getWidth() - 1) : (x);
				y = (y < 0) ? (0) : (y);
				y = (imp.getHeight() <= y) ? (imp.getHeight() - 1) : (y);
				Peak peak = (Peak)(listPoints.elementAt(currentPoint));
				peak.x = x;
				peak.y = y;
				listPoints.removeElementAt(currentPoint);
				listPoints.insertElementAt(peak, currentPoint);
			}
		} 
		
		
		/*********************************************************************
		 Change the current point.
		 ********************************************************************/
		public void nextPoint (
		) {
			currentPoint = (currentPoint == (numPoints - 1)) ? (0) : (currentPoint + 1);
		} 
		
		
		/*********************************************************************
		 This constructor stores a local copy of its parameters and initializes
		 the current spectrum. It also creates the object that takes care of
		 the interactive work.
		 @param imp <code>ImagePlus</code> object where points are being picked.
		 @param tb <code>PointToolbar</code> object that handles the toolbar.
		 ********************************************************************/
		public PointHandler (final ImagePlus imp) {
			super(0, 0, imp.getWidth(), imp.getHeight(), imp);
			this.imp = imp;
	//		this.tb = tb;
		} 
		
		
		/*********************************************************************
		 Remove the current point. Make its color available again.
		 ********************************************************************/
		public void removePoint () {
			if (0 < numPoints) {
				listPoints.removeElementAt(currentPoint);
				numPoints--;
			}
			currentPoint = numPoints - 1;
		} 
		
		
		/*********************************************************************
		 Remove all points and make every color available.
		 ********************************************************************/
		public void removePoints () {
			listPoints.removeAllElements();
			numPoints = 0;
			currentPoint = -1;
		//	tb.setTool(pointAction.ADD_PEAK);
			imp.setRoi(this);
		} 
		
		
		/*********************************************************************
		 Stores a local copy of its parameter and allows the graphical
		 operations to proceed. The present class is now fully initialized.
		 @param pa <code>pointAction</code> object.
		 ********************************************************************/
		public void setPointAction (
				final PointAction pa
		) {
			this.pa = pa;
			started = true;
		}
		
	} 

	
	/**
	 * 
	 * @author Bill Hammond
	 *
	 */
	private class PeakMapper{
		
		final int OUTSIDE_DETECTOR = 67108864;
		private Point[][] nearest = new Point[height][width];
		public float[][] zones;
		private int peaks;
		private int detDimension;
		public Set preMap = new HashSet();
		private Set postMap = new HashSet();//xCrystals*crystals);
		private Set borderPeaks = new HashSet();
		private double aveDistX, aveDistY;
		private double xMin, xMax, yMin, yMax;
		private boolean complete = false;
		Iterator it;
		
		
		public PeakMapper(){
			zones = new float[height][width];
		}
		
		public boolean isComplete(){
			return complete;
		}
		
		
		public FloatProcessor defineRegions(){
			
			int counter = 0;
			int xtalID;
			IJ.showStatus("Calculating voronoi regions...");
			createFalseBorderPeaks();
			complete = false;
			int range = largestPeakSize*2;
			if(range == 0)
				range = peakSize*2;
			
		//	for(int i = 0; i < width; i++){
		//		for(int j = 0; j < height; j++){
		//			zones[j][i] = OUTSIDE_DETECTOR;
		//		}
		//	}
			
			for (int k = 0; (k < numPoints); k++) {
				
				if(k%50 == 0){
					IJ.showProgress((new Integer(k)).doubleValue()/(new Integer(numPoints)).doubleValue());
				}

				Peak tempPeak = (Peak)listPoints.elementAt(k);
				xtalID = tempPeak.getCrystalNumber();
				defineRegion(tempPeak.x, tempPeak.y, xtalID, range);

			}
			IJ.showProgress(1.0);
			
		//	IJ.showStatus("Defining exterior crystal edges...");
			
		//	it = borderPeaks.iterator();
		//	int size = borderPeaks.size();
		//	counter = 0;
		//	while(it.hasNext()){
		//		if(counter%10 == 0)
		//			IJ.showProgress((new Integer(counter)).doubleValue()/(new Integer(size)).doubleValue());

		//		Peak tempPeak = (Peak)it.next();
		//		defineRegion(tempPeak.x, tempPeak.y, OUTSIDE_DETECTOR, Math.max(width - tempPeak.x, height - tempPeak.y));
		//		counter++;

		//	}
		//	IJ.showProgress(1.0);
			IJ.showStatus("");
			complete = true;
			
			for(int i = 1; i < (width-1); i++){
				for(int j = 1; j < (height-1); j++){
					if((zones[j][i] != zones[j][i+1]) || (zones[j][i] != zones[j][i+1]) || (zones[j][i] != zones[j+1][i]) || (zones[j][i] != zones[j-1][i]))
							rawPlusBoundaries.putPixelValue(i, j, highestPeak);
				}
			}

			return arrayToProcessor(zones);
		}
		
		
		/**
		 * utility method to convert and assign a 2d array to a 1d array and an ImageJ ImageProcessor
		 * @param inData
		 * @return
		 */
		private FloatProcessor arrayToProcessor(float[][] inData){
			float[] procData = new float[inData.length * inData[0].length];
			FloatProcessor processor = new FloatProcessor(inData[0].length, inData.length);
			for(int j = 0; j < inData.length; j++){
				for(int i = 0; i < inData[0].length; i++){
					procData[j*inData[0].length + i] = inData[j][i];
				}
			}
			processor.setPixels(procData);
			return processor;
		}
		
		
		/**
		 * 
		 *
		 */
		public void createFalseBorderPeaks(){
			
			//		- - - -
			//		| * * |
			//		| * * |
			//		- - - -
			
			Peak tempPeak;
			borderPeaks.clear();
			//top and bottom
			for(int xPos = (int)(xMin - peakSize); xPos < (int)(xMax + peakSize); xPos += peakSize){
			//	tempPeak = new Peak(xPos, (int)(yMin - peakSize));	//bottom row
			//	borderPeaks.add(tempPeak);
				defineRegion(xPos, (int)(yMin-peakSize), OUTSIDE_DETECTOR, width);
				defineRegion(xPos, (int)(yMin+peakSize), OUTSIDE_DETECTOR, width);
			//	tempPeak = new Peak(xPos, (int)(yMax + peakSize));	//top row
			//	borderPeaks.add(tempPeak);
			}
			
			//left and right
			for(int yPos = (int)yMin; yPos < (int)yMax; yPos += peakSize){
		//		tempPeak = new Peak((int)(xMin - peakSize), yPos);	//left column
		//		borderPeaks.add(tempPeak);
		//		tempPeak = new Peak((int)(xMax + peakSize), yPos);	//left column
		//		borderPeaks.add(tempPeak);
				defineRegion((int)(xMin-peakSize), yPos, OUTSIDE_DETECTOR, width);
				defineRegion((int)(xMin+peakSize), yPos, OUTSIDE_DETECTOR, width);
			}
		}
		
		
		public void defineRegion(int x, int y, int xtalID, int range) {
			Point p = new Point(x, y);
			int yMax;
			if(range > x || range > y)
				range = Math.min(x,y);
			if(x > (width-range) || y > (height-range))
				range = Math.min(width-x, height-y);
			int r2 = range*range;
			// compare each pixel (i, j) and find nearest point
			for(int i = (x - range); i < (x + range); i++){
				yMax = (int)(Math.sqrt(r2 - (x-i)*(x-i)) + 0.5);
				for(int j = (y-yMax); j < (y+yMax); j++){
					Peak q = new Peak(i, j);
					if ((nearest[i][j] == null) || (q.distanceTo(p) < q.distanceTo(nearest[i][j]))) {
						nearest[i][j] = p;
						zones[j][i] = (float)xtalID;
						System.out.println("zones[" + j + "][" + i + "] = " + zones[j][i]);
					}
				}
			}
		}
		
		
		/**
		 * Enumerates peaks with crystal numbers
		 * maps all peaks if input values equal 0,0
		 * if input values are other than 0,0 leaves in place previous enumerations less than oldID, 
		 * explicitely sets the peak that previously had oldID to newID, and starts fresh enumerating
		 * any remaining peaks greater than or equal to oldID
		 * @param oldID
		 * @param newID
		 * @return
		 */
		public boolean mapPeaks(Peak badPeak, int newID){
			
			Peak lowPeak = null;
			Peak tempPeak = null;
			Peak lastPeak = null;
			boolean successful = true;
		//	double lastRowHeight = 0;
		//	int rowCount, colCount;
			preMap.clear();
			postMap.clear();
			int currentCrystal = 0;
			
			if(badPeak == null){	//do all peaks - disregard any previous enumerations
				preMap.addAll(listPoints);
				String result = characterize();
				if(!result.equals("successful")){
					IJ.showMessage(result);
					return false;
				}
				currentCrystal = 0;
		//		rowHeight = new double[yCrystals+1];
			}
			else{	//correct problematic peak and try to enumerate again subsequent peaks
				badPeak.setCrystalNumber(newID);
				lastPeak = badPeak;
				postMap.add(badPeak);
				currentCrystal = newID + 1;
				for(int i = 0; i < numPoints; i++){
					tempPeak = (Peak)listPoints.elementAt(i);
					if(tempPeak.getCrystalNumber() < newID && tempPeak.getCrystalNumber() > -1)
						postMap.add(tempPeak);	//this peak is already correctly numbered
					else
						preMap.add(tempPeak);
				}
				preMap.remove(badPeak);
			}
			try{
				int totalCrystals = xCrystals*yCrystals;
				System.out.println("total crystals = " + totalCrystals);
				while(currentCrystal < totalCrystals){
				//	System.out.println(currentCrystal);
					if((currentCrystal == 0) || (currentCrystal % xCrystals == 0)){	//this is the first crystal in a new row
						lowPeak = findFirstPeak(rowHeight[currentCrystal / xCrystals]);
						rowHeight[(currentCrystal / xCrystals) + 1] = lowPeak.getY();
						lastPeak = lowPeak;
						if(!mapPeak(lowPeak, currentCrystal))
							return false;
					}
					else{
						//		if(currentCrystal % xCrystals == detDimension){	//separated pmts with no pixels between
						//			lowPeak = findNextDetector(lastPeak.getX(), lastPeak.getY());
						//		}
						//		else{
						lowPeak = findNext(lastPeak.getX(), lastPeak.getY());
						//		}
						lastPeak = lowPeak;
						if(!mapPeak(lowPeak, currentCrystal))
							return false;
					}
					currentCrystal++;
				}
			}catch(NullPointerException e){
				e.printStackTrace();
				return false;
			}
			return successful;
		}
		
		
		/*
		 *	Returns true if peak is successfully mapped.  If this peak does not exist or it cannot be mapped, returns false
		 */
		private boolean mapPeak(Peak peak, int row, int col){	
			try{		
				peak.setRow(row);	//assign peak row
				peak.setColumn(col);	//assign peak column
				peak.setCrystalNumber(row*xCrystals + col);
				postMap.add(peak);	//add this mapped peak to postMap, the Set of mapped peaks
				preMap.remove(peak);	//remove this mapped peak from preMap, the set of unmapped peaks
				peak = null;		//remove this reference
			}catch(NullPointerException e){
				return false;
			}
			return true;
		}
		
		/*
		 *	Returns true if peak is successfully mapped.  If this peak does not exist or it cannot be mapped, returns false
		 */
		private boolean mapPeak(Peak peak, int id){	
			try{		
				peak.setCrystalNumber(id);
				postMap.add(peak);	//add this mapped peak to postMap, the Set of mapped peaks
				preMap.remove(peak);	//remove this mapped peak from preMap, the set of unmapped peaks
				peak = null;		//remove this reference
			}catch(NullPointerException e){
				return false;
			}
			return true;
		}
		
		
		/*
		 *	Find the first peak in a new row	
		 */
		private Peak findFirstPeak(double lastY){
			
			Peak lowPeak = null;
			Peak tempPeak = null;
			double sum, dist, min;
			double xDiff, yDiff;
			double theta = 0;
			//new row, find the first peak in row without comparison to prior members
			min = width*2;
			it = preMap.iterator();
			while(it.hasNext()){
				tempPeak = (Peak)it.next();
				xDiff = tempPeak.getX();
				yDiff = tempPeak.getY() - lastY;
				theta = Math.atan(yDiff/xDiff);
				dist = Math.sqrt(Math.pow(xDiff,2) + Math.pow(yDiff,2));
				if((theta + dist/xMin) < min){		
					lowPeak = tempPeak;	//after looping through all peaks, lowPeak will be the first peak in the lowest remaining column
					min = (theta + dist/xMin);
				}
			}
			return lowPeak;
		}
		
		
		/*
		 *	Finds the next peak in the current row - must not be the first peak in a row nor the first peak in a segmented pmt
		 */
		private Peak findNext(double lastX, double lastY){
			
			Iterator it;
			Peak lowPeak = null;
			Peak tempPeak = null;
			double x, y;
			double xDiff, yDiff, dist;
			final double RANGE = aveDistX;
			double theta = 0.0;
			double min = 1000.0;
			
			it = preMap.iterator();
			
			while(it.hasNext()){	//for each mapped point, go through every peak to find minimum row and column
				
				tempPeak = (Peak)it.next();		//get the next peak
				x = tempPeak.getX();		//record x coordinate
				y = tempPeak.getY();		//record y coordinate
				xDiff = x - lastX;	//distance in x between this peak and last mapped peak, weighted by factor of 2	
				yDiff = y - lastY;		//distance in y between this peak and last mapped peak, un-weighted
				
				
				if(xDiff > 0 && yDiff < RANGE){		//first stipulation - next peak in row must lie to right of lastPeak and within RANGE of lastPeak.getY()
					
					theta = Math.atan(yDiff/xDiff);
					dist = Math.sqrt(Math.pow(xDiff,2) + Math.pow(yDiff,2));
					if((theta*1.2 + dist/aveDistX) < min){
						lowPeak = tempPeak;
						min = (theta*1.2 + dist/aveDistX);	//this will be the closest peak to lastPeak in x; must now check if there are any other peaks closer in y
					}
				}
			}
			
			return lowPeak;	
		}
		
		
		/*
		 *	If the crystal array is segmented into multiple pmt regions, this method must be called when spanning the gap from one pmt to another
		 */
		private Peak findNextDetector(double lastX, double lastY){
			
			Iterator it;
			Peak lowPeak = null;
			Peak tempPeak = null;
			double x, y;
			double xDiff, yDiff, dist;
			final double RANGE = aveDistX*2;
			double theta = 0.0;
			double min = 1000.0;
			
			it = preMap.iterator();
			
			while(it.hasNext()){	//for each mapped point, go through every peak to find minimum row and column
				
				tempPeak = (Peak)it.next();		//get the next peak
				x = tempPeak.getX();		//record x coordinate
				y = tempPeak.getY();		//record y coordinate
				xDiff = x - lastX;	//distance in x between this peak and last mapped peak, weighted by factor of 2	
				yDiff = y - lastY;		//distance in y between this peak and last mapped peak, un-weighted
				
				
				if(xDiff > aveDistX && yDiff < RANGE){		//first stipulation - next peak in row must lie to right of lastPeak and within RANGE of lastPeak.getY()
					
					theta = Math.atan(yDiff/xDiff);
					dist = Math.sqrt(Math.pow(xDiff,2) + Math.pow(yDiff,2));
					if((theta*1.5 + dist/aveDistX) < min){
						lowPeak = tempPeak;
						min = (theta*1.5 + dist/aveDistX);	//this will be the closest peak to lastPeak in x; must now check if there are any other peaks closer in y
					}
				}
			}
			return lowPeak;	
		}	
		
		
		/*
		 *	Before mapping, scan peaks to find parameters necessary for mapping algorithm	
		 *		**This must be called before calling mapPeaks()
		 */
		public String characterize(){
			xMin = width;
			xMax = 0;
			yMin = height; 
			yMax = 0;
			Peak tempPeak;
			Iterator it = preMap.iterator();
			peaks = 0;
			while(it.hasNext()){			//find number of peaks and average distance between peaks
				peaks++;
				tempPeak = (Peak)it.next();
				if(tempPeak.getX() < xMin)	//pick out the lowest peak in x
					xMin = tempPeak.getX();
				if(tempPeak.getX() > xMax)	//pick out the highest peak in x
					xMax = tempPeak.getX();
				if(tempPeak.getY() < yMin)
					yMin = tempPeak.getY();
				if(tempPeak.getY() > yMax)
					yMax = tempPeak.getY();
			}
			int expected = xCrystals*yCrystals;
			if(peaks > expected)
				return ("Too many peaks: expected " + expected + ", found " + peaks);
			if(peaks < expected)
				return ("Too few peaks: expected " + expected + ", found " + peaks);
			
			aveDistX = (xMax - xMin) / xCrystals;
			aveDistY = (yMax - yMin) / yCrystals;
//			System.out.println("crystals = " + crystals + " ; aveDistX = " + aveDistX);
			
			//find gaps between pmt, if they exist at all
			Iterator it2;
			it = preMap.iterator();
			Peak currentPeak;
			double currentX, currentY;
			
			int xBins = (int)((xMax - xMin) / aveDistX);
			int yBins = (int)((yMax - yMin) / aveDistY);
			int[] gapBinsX = new int[xBins+1];
			int[] gapBinsY = new int[yBins+1];
			int counter = 0;
			int hasLocalX = 0, hasLocalY = 0;
			int totalLocalX = 0, totalLocalY = 0;
			double aveLocalX, aveLocalY;
			while(it.hasNext()){
				counter++;
				currentPeak = (Peak)it.next();
				currentX  = currentPeak.getX();
				currentY = currentPeak.getY();
				it2 = preMap.iterator();
				while(it2.hasNext()){
					
					tempPeak = (Peak)it2.next();
					if((tempPeak.getX() - currentX) < aveDistX*1.5 && (tempPeak.getX() - currentX) > (aveDistX*1.0))
						hasLocalX++;
					if((tempPeak.getY() - currentY) < aveDistY*1.5 && (tempPeak.getY() - currentY) > (aveDistY*1.0))
						hasLocalY++;
				}
				totalLocalX += hasLocalX;
				totalLocalY += hasLocalY;
				aveLocalX = ((double)totalLocalX / (double)counter);
				aveLocalY = ((double)totalLocalY / (double)counter);
				
				if(hasLocalX < (int)(aveLocalX*0.1))
					gapBinsX[(int)((currentPeak.getX()-xMin)/aveDistX)]++;
				if(hasLocalY < (int)(aveLocalY*0.1))
					gapBinsY[(int)((currentPeak.getY()-yMin)/aveDistY)]++;
				
				hasLocalX = 0;
				hasLocalY = 0;
			}
			
			int xGaps = 0, yGaps = 0;
			boolean adjacentHit = false;
			for(int i = 1; i < xBins-1; i++){
				if(gapBinsX[i] > 0 && !adjacentHit){
					xGaps++;
					System.out.println("xGap at " + i);
					adjacentHit = true;
				}
				else
					adjacentHit = false;
			}
			adjacentHit = false;
			for(int j = 1; j < yBins-1; j++){
				if(gapBinsY[j] > 0 && !adjacentHit){
					yGaps++;
					System.out.println("yGap at " + j);
					adjacentHit = true;
				}
				else
					adjacentHit = false;
			}
			if(xGaps == 0)
				detDimension = width;
			else
				detDimension = xCrystals / (xGaps + 1);
			
			detDimension = width;
			System.out.println("xGaps = " + xGaps);
			System.out.println("yGaps = " + yGaps);		
			
			return "successful";
		}
		
		
	}//end PeakMapper class
	
	
	/**
	 * Peak class - same as a Point, except with addition of crystal number, row, column, and a distanceTo
	 * function used to find distance between peaks
	 * @author Bill Hammond
	 *
	 */
	public class Peak extends Point{

		private int row;
		private int col;
		private int crystalNumber = -1;
		private Peak neighbor;
		private double nearest;
		private int value = 0;
		
		public Peak(int x, int y){
			super(x, y);
		}
		
		public Peak(int x, int y, int value){
			super(x, y);
			this.value = value;
		}
		
		public void setValue(int value){
			this.value = value;
		}
		
		public int getValue(){
			return value;
		}
		
		public void setRow(int row){
			this.row = row;
		}
		
		public void setColumn(int col){
			this.col = col;
		}
		
		public int getRow(){
			return row;
		}
		
		public int getColumn(){
			return col;
		}
		
		public void setCrystalNumber(int id){
			crystalNumber = id;
		}
		
		public int getCrystalNumber(){
			return crystalNumber;
		}
		
		public Peak getNearestPeak(Set set){
			Iterator it = set.iterator();
			Peak tempPeak;
			neighbor = null;
			nearest = 99999999;
			double dist;
			while(it.hasNext()){
				tempPeak = (Peak)it.next();
				dist = distanceTo(tempPeak);
				if(dist < nearest){
					neighbor = tempPeak;
					nearest = dist;
				}
			}
			return neighbor;		
		}
		
		public double getNearestDist(Set set){
			getNearestPeak(set);
			return nearest;
		}
		
		// return Euclidean distance between invoking point p and q
		public double distanceTo(Point q) {
			Point p = this;
			double dx = p.x - q.x;
			double dy = p.y - q.y;
			return Math.sqrt(dx*dx + dy*dy);
		}
		
	}

}

