import java.io.*;
import java.util.*;

@SuppressWarnings("unused")
public class TFSDiskInputOutput 
{
	static final int BLOCK_SIZE = 128;
	static RandomAccessFile raf = null;
	
	/*
	 * Disk I/O API
	 */
	 
	public static int tfs_dio_create(byte[] name, int nlength, int size) 
	{
		try {
			File f = new File(new String(name, 0, nlength));
			f.createNewFile();
			raf = new RandomAccessFile(f, "rw");  

			raf.setLength(size * BLOCK_SIZE);
		
			System.out.println("tfs_dio_create: " + raf.length() + " file created");
		} catch (IOException ie) {}
		
		return 0;
	}	
	
	public static int tfs_dio_open(byte[] name, int nlength) 
	{
		try {
			File f = new File(new String(name, 0, nlength));
			if (!f.exists())
				return -1;
		
			raf = new RandomAccessFile(f, "rw");  

			System.out.println("tfs_dio_open: " + raf.length() + " size file opened");
		} catch (IOException ie) {}
		
		return 0;
	}			
	
	//Returns disk size in blocks
	public static int tfs_dio_get_size() 
	{
		try {
			return (int)(raf.length() / BLOCK_SIZE);
		} catch (IOException ie) {}
		
		return 0;
	}							
	
	public static int tfs_dio_read_block(int block_no, byte[] buf) 
	{
		try {
			if (buf.length < BLOCK_SIZE)
				return -1;
			
			raf.seek(block_no * BLOCK_SIZE);
			raf.read(buf, 0, BLOCK_SIZE);
		} catch (IOException ie) {}
		
		return 0;
	}
	
	public static int tfs_dio_write_block(int block_no, byte[] buf)	
	{
		try {
			if (buf.length < BLOCK_SIZE)
				return -1;
			
			raf.seek(block_no * BLOCK_SIZE);
			raf.write(buf, 0, BLOCK_SIZE);
		} catch (IOException ie) {}
		
		return 0;
	}
	
	public static void tfs_dio_close() 
	{
		try {
			if (raf != null)
				raf.close();
		} catch (IOException ie) {}
			
		return;
	}					
}