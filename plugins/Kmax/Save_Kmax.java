import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;


public class Save_Kmax implements PlugIn {
	
	
	
	public void run(String arg){
		
		try{
			ImagePlus imp = WindowManager.getCurrentImage();
			FloatProcessor processor = (FloatProcessor)imp.getProcessor();
		//	processor.flipVertical();	//this is done below by going through processor pixels (in imagej 0 is at top, but kmax is at bottom)
			int width = processor.getWidth();
			int height = processor.getHeight();
			float[] data = (float[])processor.getPixels();
			SaveDialog od = new SaveDialog("Save as a Kmax txt file:", imp.getTitle() + ".txt", ".txt");
			String directory = od.getDirectory();
			String name = od.getFileName();
			String path = "";
			if (name!=null) {
				path = directory+name;
				Menus.addOpenRecentItem(path);
			}    
			else
				return;
			BufferedWriter bwText = new BufferedWriter(new FileWriter(path, false));
			PrintWriter outText = new PrintWriter(bwText);
			int counter = 0;
			outText.println("CHO 2");
			outText.print("" + width + " ");
			outText.println("" + height);
			for(int j = 0; j < height; j++){
				for(int i = 0; i < width; i++){
					outText.print("" + Long.toString(Math.round(processor.getPixelValue(i,j))) + " ");
					counter++;
					if(counter%10 == 0)
						outText.println();
				}
			}
			outText.close();
		}catch(IOException ioe){
			ioe.printStackTrace();
		}
		catch(Exception e){
			IJ.showMessage("Image must be 32-bit grayscale.");
		}
	
	}
	
}
