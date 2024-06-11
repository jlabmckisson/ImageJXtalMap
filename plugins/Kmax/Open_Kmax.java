import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.FloatProcessor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;


public class Open_Kmax implements PlugIn {
	
	int xDim, yDim;
	
	public void run(String arg){
		
		String filename = File.separator+"txt";
		
		OpenDialog od = new OpenDialog("Choose a Kmax txt file:", "");
		String directory = od.getDirectory();
		String name = od.getFileName();
		String path = "";
		if (name!=null) {
			path = directory+name;
			Menus.addOpenRecentItem(path);
		}    
		else
			return;
		
		float[] data = convertText(new File(path));
		
		int dim = (int)Math.sqrt((double)data.length);
		FloatProcessor processor = new FloatProcessor(xDim, yDim);
		processor.setPixels(data);
		
		//flip the image vertically to match view in Kmax
	//	processor.flipVertical();
		
		//convert to an ImageJ ImagePlus
		ImagePlus image = new ImagePlus(name, processor);
		
		//convert image from 16 bit to 32 bit
//		ImageConverter convert = new ImageConverter(image);
//		convert.convertToGray32();
		
		//display the image
		image.show();
		image.draw();
	}
	
	private float[] convertText(File kmaxTxtFile){
		
		String temp;
		String delim = " ";
		int counter = 0;
		float[] data = null;
		StringTokenizer st = null;
		BufferedReader in = null;
		
		try{	
			in = new BufferedReader(new FileReader(kmaxTxtFile));
			temp = in.readLine();			//skip kmax header line "CHO	2" 
			if(temp.charAt(3) == ' ')
				delim = " ";
			else
				delim = "\t";
			temp = in.readLine();	//read header line with dimensions
			st = new StringTokenizer(temp, delim);
			xDim = Integer.parseInt(st.nextToken());
			yDim = Integer.parseInt(st.nextToken());
			
		}catch(IOException e){e.printStackTrace();}
		
		st = null;
		data = new float[xDim*yDim];
		
		try{
			
			while ((temp = in.readLine()) != null) {
				
				st = new StringTokenizer(temp, delim);
				
				while(st.hasMoreTokens()){
					data[counter] = Float.parseFloat(st.nextToken());
					counter++;
				}	
			}
			in.close();
			
		} catch (IOException e) {System.out.println(e);}
		
		return data;
	}
}
