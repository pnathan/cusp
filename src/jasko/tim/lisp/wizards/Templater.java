package jasko.tim.lisp.wizards;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

import org.eclipse.core.runtime.Platform;

public class Templater {
	private static String[] inspirations = new String[] {
		"This is your lisp file. May it serve you well.",
		"This is your lisp file. There are many like it, but this one is yours.",
		"Behold, the power of lisp."
	};
	
	
	public static InputStream getTemplate(String fileName, String pkg) {
		try {
			URL installURL = Platform.getBundle("jasko.tim.lisp").getEntry("/");
			URL url = new URL(installURL, "templates/" + fileName);
			BufferedReader template = new BufferedReader(new InputStreamReader(url.openStream()));
			
			StringBuilder sb = new StringBuilder();
			String line = template.readLine();
			
			while (line != null) {
				sb.append(line);
				sb.append('\n');
				line = template.readLine();
			}
			
			String contents = sb.toString();
			
			contents = contents.replace("${inspiration}", getInspiration());
			contents = contents.replace("${time}", getTime());
			contents = contents.replace("${package}", pkg);
			
			return new ByteArrayInputStream(contents.getBytes());
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return new ByteArrayInputStream("".getBytes());
	}
	
	private static String getTime() {
		Calendar cal = Calendar.getInstance(TimeZone.getDefault());
		String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
		java.text.SimpleDateFormat sdf =
			new java.text.SimpleDateFormat(DATE_FORMAT);
		sdf.setTimeZone(TimeZone.getDefault());
		
		return sdf.format(cal.getTime());
	}

	private static String getInspiration() {
		Random rand = new Random();
		return inspirations[rand.nextInt(inspirations.length)];
		//InputStream contents = new ByteArrayInputStream(header.getBytes());
	}
}
