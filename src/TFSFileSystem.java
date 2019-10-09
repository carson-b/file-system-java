import java.io.*;
import java.util.*;
import java.nio.*;
@SuppressWarnings("unused")

public class TFSFileSystem
{
	/*
	 * constants
	 */

	static final String DISK_FILE = "TFSDiskFile";
	static final int DISK_FILE_SIZE = 2048;  // blocks; should be exact times of BLOCK_SIZE
	static final int BLOCK_SIZE = 128;  // blocks; should be exact times of 4
	static final int FS_MAGIC = 777;
	static final int MAX_ENTRY_DIR = 4;  // the maximum number of entries in a block for a directory
	static final int DIR_ENTRY_SIZE = 28;  // the size of each entry in a directory block
	static final int FDT_SIZE = 100;  // the maximum number of entries in FDT
	static final int MAX_NAME_LENGTH = 15; //the maximum number of bytes a name can be
	static final int PARENTBN = 2;	//the entry offset for parent_block_no
	static final int IS_DIR = 4; //the entry offset for is_directory
	static final int NAME = 5; //the entry offset for name
	static final int NLENGTH = 21; //the entry offset for nlength
	static final int RESERVED1 = 22; //the entry offset for reserved1
	static final int RESERVED2 = 23; //the entry offset for reserved2
	static final int FBN = 24; //the entry offset for first block no
	static final int SIZE = 26; // the entry offset for size

	/*
	 * In-memory data structures
	 */

	// flags

	private static boolean fs_mounted = false;
	private static boolean fs_opened = false;

	// PCB

	private static int pcb_pointer_root;  // the first block number for the root directory
	private static int pcb_pointer_free;  // the first free block number
	private static int pcb_size_fs;  // the total number of blocks in the file system
	private static int pcb_size_fat;  // the total number of blocks in FAT
	private static int pcb_magic = 0;  // magic to see if the fs on the disk is an TFS file system

	// FAT

	private static int[] fat;  // FAT32

	// FDT

	private static boolean[] fdt_is_free = new boolean[FDT_SIZE];
	private static boolean[] fdt_is_directory = new boolean[FDT_SIZE];
	private static byte[][] fdt_name = new byte[FDT_SIZE][16];
	private static int[] fdt_nlength = new int[FDT_SIZE];
	private static int[] fdt_file_pointer = new int[FDT_SIZE];
	private static int[] fdt_first_block_no = new int[FDT_SIZE];
	private static int[] fdt_parent_block_no = new int[FDT_SIZE];
	private static int[] fdt_size = new int[FDT_SIZE];

	/*
	 * directory structure in TFS file system
	 */

	// the total number of files and sub-directories
	//entries for files or sub-directories, and each entry has
	static int noFiles;
	static int parentBlockNo; //Block number of the parent directory
	static byte isDirectory;  // 0: sub-directory, 1: file
	static byte nLength;  // name length
	static byte reserved1;  // reserved
	static byte reserved2;  // reserved
	static byte[] name = new byte[16];
	static int firstBlockNo;  // the first block number
	static int size;  // the size of the file or sub-directory
		// the size of each entry is 4 + 16 + 4 +4 = 28 bytes
	// 128 % 28 = 4 => one block can hold maximum 4 entries
	


	/*
	 * TFS Constructor
	 */

	public TFSFileSystem()
	{
		for (int i = 0; i < FDT_SIZE; i++)
			fdt_is_free[i] = true;
	}


	/*
	 * TFS API
	 */

	public static int tfs_mkfs()
	{
		// if the file system is mounted, then return error

		if (fs_mounted)
			return -1;

		// if the disk file system is not open, then open

		if (!fs_opened)
			if (TFSDiskInputOutput.tfs_dio_open(DISK_FILE.getBytes(), DISK_FILE.length()) < 0)
				TFSDiskInputOutput.tfs_dio_create(DISK_FILE.getBytes(), DISK_FILE.length(), DISK_FILE_SIZE);
		fs_opened = true;

		// decide the size of the disk and the size of FAT

		pcb_size_fs = TFSDiskInputOutput.tfs_dio_get_size();
		pcb_size_fat = pcb_size_fs * 4 / BLOCK_SIZE;

		// new FAT

		fat = new int[pcb_size_fs];
		for (int i = 0; i < pcb_size_fs; i++)
			fat[i] = -1;

		// initialize FAT

		pcb_pointer_root = 2 + pcb_size_fat;  // 0: BCB, 1: PCB, blocks for FAT, ...
		fat[pcb_pointer_root] = -1;  // the first block for the root directory
		pcb_pointer_free = pcb_pointer_root + 1;  // the free block list
		for (int i = pcb_pointer_free; i < pcb_size_fs -1; i++)
			fat[i] = i+1;
		fat[pcb_size_fat-1] = -1;

		// mark TFS file system

		pcb_magic = FS_MAGIC;

		// write PCB and FAT back into the disk

		_tfs_write_pcb();
		_tfs_write_fat();

		// initialize the root directory

		byte[] block = new byte[BLOCK_SIZE];
		_tfs_put_int_block(block, 0, 0);  // block offset 0; no_entries 0
		_tfs_write_block(pcb_pointer_root, block);
		
		System.out.println("mkfs successful!");
		return 0;
	}


	public static int tfs_mount()
	{
		//if the file system is already mounted or is not opened return an error
		if(fs_mounted || !fs_opened)
			return -1;
		//Read PCB from disk into memory
		_tfs_read_pcb();
		//Read FAT from disk into memory
		_tfs_read_fat();
		//Set fs_mounted to true
		fs_mounted = true;
		System.out.println("mount successful!");
		return 0;
	}


	public static int tfs_umount()
	{
		//if the file system is already unmounted or is not opened return an error
		if(!fs_mounted || !fs_opened)
			return -1;
		//Write PCB and FAT from memory back into the disk
		tfs_sync();
		//Set fs_mounted to false
		fs_mounted = false;
		System.out.println("umount successful!");
		return 0;
	}


	public static int tfs_sync()
	{
		//If the file system is not opened or not mounted return an error
		if(!fs_opened || !fs_mounted)
			return -1;
		//Read PCB from memory back into disk
		_tfs_write_pcb();
		//Read FAT from memory back into disk
		_tfs_write_fat();
		System.out.println("sync successful!");
		return 0;
	}


	/*
	 * print raw FS
	 */

	public static String tfs_prrfs()
	{
		// return if the disk file is not open yet

		if (!fs_opened)
			return null;

		// get PCB from disk

		int size_fs, size_fat, pointer_free, pointer_root;

 		byte[] bblock = new byte[BLOCK_SIZE];

 		TFSDiskInputOutput.tfs_dio_read_block(1, bblock);

 		ByteBuffer bbuf = ByteBuffer.wrap(bblock);

 		size_fs = bbuf.getInt();
 		size_fat = bbuf.getInt();
 		pointer_free = bbuf.getInt();
 		pointer_root = bbuf.getInt();

		String output = "";

		output += "Partition Control Block:\n";
		output += "    The size of FS = " + size_fs + "\n";
		output += "    The size of FAT = " + size_fat + "\n";
		output += "    The pointer to FREE = " + pointer_free + "\n";
		output += "    The pointer to ROOT = " + pointer_root + "\n";

		// get FAT from disk

		int[] fat_save = new int[fat.length];
		for(int i = 0; i < fat.length; i++)
			fat_save[i] = fat[i];

		_tfs_write_fat();
		_tfs_read_fat();
		output += "File Allocation Table:\n";
		output += "    FAT.length = " + fat.length + "\n";
		output += "    FAT[0] = " + fat[0] + "\n";
		output += "    FAT[1] = " + fat[1] + "\n";
		output += "    FAT[2] = " + fat[2] + "\n";
		output += "    FAT[pointer_root:" + pointer_root + "] = " + fat[pointer_root] + "\n";
		output += "    FAT[pointer_free:" + pointer_free + "] = " + fat[pointer_free] + "\n";
		output += "    FAT[pointer_free+1] = " + fat[pointer_free+1] + "\n";
		output += "    FAT[fat.length-3] = " + fat[fat.length-3] + "\n";
		output += "    FAT[fat.length-2] = " + fat[fat.length-2] + "\n";
		output += "    FAT[fat.length-1] = " + fat[fat.length-1] + "\n";

		for(int i = 0; i < fat.length; i++)
			fat[i] = fat_save[i];

		// return the result

		return output;
	}


	/*
	 * print mounted FS
	 */

	public static String tfs_prmfs()
	{
		// return if the disk file is not mounted yet
		if (!fs_mounted)
			return null;

		String output = "";

		//Add PCB info
		output += "Partition Control Block:\n";
		output += "    The size of FS = " + pcb_size_fs + "\n";
		output += "    The size of FAT = " + pcb_size_fat + "\n";
		output += "    The pointer to FREE = " + pcb_pointer_free + "\n";
		output += "    The pointer to ROOT = " + pcb_pointer_root + "\n";

		//Add FAT info
		output += "File Allocation Table:\n";
		output += "    FAT.length = " + fat.length + "\n";
		output += "    FAT[0] = " + fat[0] + "\n";
		output += "    FAT[1] = " + fat[1] + "\n";
		output += "    FAT[2] = " + fat[2] + "\n";
		output += "    FAT[pointer_root:" + pcb_pointer_root + "] = " + fat[pcb_pointer_root] + "\n";
		output += "    FAT[pointer_free:" + pcb_pointer_free + "] = " + fat[pcb_pointer_free] + "\n";
		output += "    FAT[pointer_free+1] = " + fat[pcb_pointer_free+1] + "\n";
		output += "    FAT[fat.length-3] = " + fat[fat.length-3] + "\n";
		output += "    FAT[fat.length-2] = " + fat[fat.length-2] + "\n";
		output += "    FAT[fat.length-1] = " + fat[fat.length-1] + "\n";

		// return the result
		return output;
	}


	public static void tfs_exit()
	{
		tfs_umount();
		TFSDiskInputOutput.tfs_dio_close();
	}

	//Checks FDT for open files given first block #
	//If open file found, return fd
	public static int tfs_check_fdt(int first_block_no)
	{
		//Check if file or directory is already open by checking FDT for entry that has first_block_no
		for(int fd = 0; fd < FDT_SIZE; fd++) {
			//Find fd entries that are not empty and the first block number is the same
			if( !fdt_is_free[fd] && fdt_first_block_no[fd] == first_block_no) {
				//If a match is found return fd
				return fd;
			}
		}
		//If no match is found return -1
		return -1;
	}
	
	//Function for opening the root directory
	public static int _tfs_open_root()
	{
		int fd;
		//Check if root is already open
		fd = tfs_check_fdt(pcb_pointer_root);
		
		if(fd > -1)
			return fd;
		
		//If the root is not open then we need to open it
		if(fd == -1)
		{
			String root = "root";
			//Create an fd in the FDT for root
			fd = _tfs_open_fd(root.getBytes(), root.getBytes().length, pcb_pointer_root, pcb_pointer_root,
					_tfs_get_root_size(), (byte)0);
			if(fd > -1)
				return fd;
		}
		return -1;
	}
	
	//name is full path to file/directory
	//returns fd
	public static int tfs_open(byte[] name, int nlength)
	{
		//Check if file exists by searching name from the root directory
		//Get first block_no of parent directory of file/directory
		int block_no;
		int fd;
		String s = new String(name);
		String slash = "/";
		block_no = _tfs_search_dir(name, nlength);
		
		//if name is / then open root and return its fd
		if(s.equals(slash))
			return _tfs_open_root();
		//If not root check if file/directory exists
		if(block_no < 0)
			return -1;
		
		//If it exists we can copy the entry into memory
		int[] fbn = new int[1];
		byte[] is_directory = new byte[1];
		int[] size = new int[1];

		//_tfs_entry_dir does not take the full path, we must send it only the filename itself
 		s = s.trim();
 		byte[] file_name = _tfs_extract_filename(name);
 		//Get the entry
		int get_entry_dir_result = _tfs_get_entry_dir(block_no, file_name, (byte)file_name.length, is_directory, fbn, size);
		if(get_entry_dir_result < 0)
			return -1;
			
		//Check if file or directory is already open by checking FDT
		fd = tfs_check_fdt(fbn[0]);
		//If fd is greater than -1 then file is already open and we can return fd
		if(fd > -1)
			return fd;
		
		//File is not already open -> create an FDT entry for it
		fd = _tfs_open_fd(name, nlength, fbn[0], block_no, size[0], is_directory[0]);
		
		//Return fd if this process is successful
		if(fd > -1) {
			System.out.println("File/Dir: " + s + " opened!");
			return fd;
		} else {
			System.out.println("Too many files opened!");
			return -1;
		}
	}
	
	//Get the size of the root directory
	private static int _tfs_get_root_size()
	{
		byte[] root = new byte[BLOCK_SIZE];
		_tfs_read_block(pcb_pointer_root, root);
		return _tfs_get_int_block(root, 0);
	}
	
	//Public access for size given fd
	public static int tfs_get_size(int fd)
	{
		return fdt_size[fd];
	}
	
	//Public access for no_entries given fd
	public static int tfs_get_no_entries(int fd)
	{
		byte[] block = new byte[BLOCK_SIZE];
		_tfs_read_block(fdt_first_block_no[fd], block);
		return _tfs_get_int_block(block, 0);
	}
	
	//Rename file found at fd to name
	//original path name is full path to original file
	//new name is not a full path
	public static int tfs_edit_name(int fd, byte[] new_name, byte[] original_path_name)
	{
		if(fd < 0 || fd > FDT_SIZE - 1 || new_name.length > 15)
			return -1;
		
		//Get original filename
		byte[] original_name = _tfs_extract_filename(original_path_name);

		//Change name in FDT
		fdt_name[fd] = new_name;
		byte[] block = new byte[BLOCK_SIZE];
		//Get the block with entry in it
		int[] entry_block_no = new int[1];
		int entry_no = _tfs_get_entry_location(fdt_parent_block_no[fd], original_name, entry_block_no);
		//Read the entry block int memory
		_tfs_read_block(entry_block_no[0], block);
		//Change the name
		_tfs_put_bytes_block(block, entry_no * DIR_ENTRY_SIZE + 5, new_name, new_name.length);
		//Write block back to disk
		_tfs_write_block(entry_block_no[0], block);
		return 0;
	}

	//Read blength bytes from file found at fd into buf
	public static int tfs_read(int fd, byte[] buf, int blength)
	{
		return _tfs_read_bytes_fd(fd, buf, blength);
	}

	//Write blength bytes from buf into file found at fd
	public static int tfs_write(int fd, byte[] buf, int blength, byte[] path)
	{
		int bytes_written = _tfs_write_bytes_fd(fd, buf, blength);
		if(bytes_written > 0)
			_tfs_update_dir_sizes(path, bytes_written);
		return bytes_written;
	}


	public static int tfs_seek(int fd, int position)
	{
		return _tfs_seek_fd(fd, position);
	}

	//Close given file
	public static void tfs_close(int fd)
	{
		_tfs_close_fd(fd);
		return;
	}

	//Create empty file with name
	//name is a full path
	//Return fd if file successfully created
	//Return -1 if file not created
	public static int tfs_create(byte[] name, int nlength)
	{
 		if (name[0] != '/')
 			return -1;
		//We need to determine if the file already exists within the supplied path
 		//Check if file already exists by searching
 		//If a block-no is returned then the file already exists
 		int block_no = _tfs_search_dir(name, nlength);
 		if(block_no > -1)
 		{
 			System.out.println("File/Dir already exists in path!");
 			return -1;
 		}
 		
 		//We can now create the file as long as the parent directory exists
		//Extract path of parent directory and desired file name from name

 		String s = new String(name);
 		s = s.trim();
 		
 		//Extract the parent directory path from the full path
 		byte[] parent_dir_path = get_parent_path(name);
 		String parent_path_s = new String(parent_dir_path);

		//Search for parent directory to see if it exists
 		//If a block number is returned then we can create a new file within it
 		//If not return an error
 		if(_tfs_search_dir(parent_dir_path, parent_dir_path.length) < 0)
 			return -1;
 		
 		//Extract the filename from the full path
 		byte[] file_name = _tfs_extract_filename(name);
 		
 		//open the parent directory
 		int parent_fd = tfs_open(parent_dir_path, parent_dir_path.length);
 		
 		//Check if directory
 		if(fdt_is_directory[parent_fd] == false)
 		{
 			System.out.println(parent_path_s + " is not a directory!");
 			return -1;
 		}
 		if( parent_fd < 0)
 			return -1;

 		
		//Now we can create the file block
 		int newBlockNo;
		// Find a free block using FAT
 		newBlockNo = _tfs_get_block_fat();
 		if(newBlockNo < 0) {
 			System.out.println("Disk is full!");
 			return -1;
 		}
 		//Create directory entry in parent directory for this new file block
 		//Also open the file to get the fd
 		if(_tfs_create_entry_dir(fdt_first_block_no[parent_fd], file_name, (byte)file_name.length,
 				(byte)1, newBlockNo, 0 ) > -1){
 			int fd = _tfs_open_fd(file_name, file_name.length, newBlockNo, fdt_first_block_no[parent_fd], 0, (byte)1);
 			//Update parent directories sizes
 			_tfs_update_dir_sizes(name, fdt_size[fd]);
 			return fd;
 		}
 		
		return -1;
	}
	
	//Copy a file from one directory to another
	//source path is full path to file to be copied
	//destination path is path to directory file to be copied to
	public static int tfs_copy_file(byte[] source_path, byte[] destination_path)
	{
		//Check if file exists by attempting to open it
				int source_fd;
				source_fd = TFSFileSystem.tfs_open(source_path, source_path.length);
				if(source_fd == -1)
				{
					System.out.println("Source file does not exist!");
					return -1;
				}
				
				//Check if file is a directory, return error if so
				if(fdt_is_directory[source_fd])
				{
					String source = new String(source_path);
					System.out.println(source + " is a directory!");
					return -1;
				}
				
				//Extract filename from source_path and build destination path including the filename
				byte[] file_name = _tfs_extract_filename(source_path);
				String file_name_s = new String(file_name);
				String dest_path_s = new String(destination_path);
				String dest_path_file = dest_path_s + "/" + file_name_s;
				
				
				//Check if filename exists in destination directory by attempting to open it
				if(tfs_open(dest_path_file.getBytes(), dest_path_file.getBytes().length) != -1)
				{
					System.out.println("A file with this name already exists in destination directory!");
					return -1;
				}
				//If the file name doesn't already exist in the directory we can proceed with copying

				//Read all of the files data blocks into memory
				byte[] file_data = new byte[fdt_size[source_fd]];
				tfs_read(source_fd, file_data, fdt_size[source_fd]);
				
				//Create file in destination directory and get fd
				int new_file_fd;
				new_file_fd = TFSFileSystem.tfs_create(dest_path_file.getBytes(), dest_path_file.getBytes().length);
				if(new_file_fd < 0)
				{
					System.out.println("File could not be created in destination directory!");
					return -1;
				}
				//We need to update its entry information in the parent block
				 _tfs_update_entry_dir(fdt_parent_block_no[new_file_fd], fdt_name[source_fd], (byte)fdt_nlength[source_fd],
						 fdt_is_directory[source_fd], fdt_first_block_no[new_file_fd], fdt_size[source_fd]);
				
				//Write file data from source file to destination file
				if(tfs_write(new_file_fd, file_data, file_data.length, dest_path_file.getBytes()) < 0)
				{
					System.out.println("File write error! File not copied!");
				}
				
				//close both files
				TFSFileSystem.tfs_close(source_fd);
				TFSFileSystem.tfs_close(new_file_fd);
				
				return 0;
	}
	
	

	//Delete given file
	public static int tfs_delete(byte[] name, int nlength)
	{
		//To delete a file we must erase its directory entry and
		//return any data blocks to the list of free blocks
		//Then update the size of its parent directory
		String s = new String(name);
		s = s.trim();
		//Determine if the file exists by searching for it and getting the parent_block_no
		int parent_block_no = _tfs_search_dir(name, nlength);
		if(parent_block_no < 0)
		{
			System.out.println(s + " does not exist!");
			return -1;
		}
		
		//If file exists we can now open it
		int fd = tfs_open(name, nlength);
		if(fd < 0)
		{
			System.out.println("FDT error!");
			return -1;
		}
		
		//If the file is a directory then use tfs_delete_dir
		if(fdt_is_directory[fd])
			tfs_delete_dir(name, nlength);
		
		//Release all blocks allocated to entry
		_tfs_return_blocks_fd(fd);
		
		//Update parent directories sizes
		_tfs_update_dir_sizes(name, fdt_size[fd] * -1);
		
		//Delete the entry for the file
		//_tfs_delete_entry_dir does not take the full path
		//We need to get just the file name<---------------------------------------------
 		byte[] file_name = _tfs_extract_filename(name);
 		
 		//Delete the entry in the parent block
		_tfs_delete_entry_dir(parent_block_no, file_name, (byte)file_name.length);
		
		//Free FD
		_tfs_free_fdt(fd);
		
		return 0;
	}

	//Create a directory block and directory entry
	public static int tfs_create_dir(byte[] name, int nlength)
	{
		if (name[0] != '/')
 			return -1;
		//We need to determine if the directory already exists within the supplied path
 		//Check if directory already exists by searching
 		//If a block-no is returned then the directory already exists
 		int block_no = _tfs_search_dir(name, nlength);
 		if(block_no > -1)
 		{
 			System.out.println("Directory already exists in path!");
 			return -1;
 		}
 		
 		//We can now create the directory as long as the parent directory exists
		//Extract path of parent directory and desired file name from name

 		String s = new String(name);
 		s = s.trim();
 		
 		//Extract the parent directory path from the full path
 		byte[] parent_dir_path = get_parent_path(name);
 		String parent_path_s = new String(parent_dir_path);

		//Search for parent directory to see if it exists
 		//If a block number is returned then we can create a new directory within it
 		//If not return an error
 		if(_tfs_search_dir(parent_dir_path, parent_dir_path.length) < 0)
 			return -1;
 		
 		//Extract the directory name from the full path
 		byte[] file_name = _tfs_extract_filename(name);
 		
 		//open the parent directory
 		int parent_fd = tfs_open(parent_dir_path, parent_dir_path.length);
 		
 		//Check if directory
 		if(fdt_is_directory[parent_fd] == false)
 		{
 			System.out.println(parent_path_s + " is not a directory!");
 			return -1;
 		}
 		if( parent_fd < 0)
 			return -1;

 		
		//Now we can create the directory block
 		int newBlockNo;
		// Find a free block using FAT
 		newBlockNo = _tfs_get_block_fat();
 		if(newBlockNo < 0) {
 			System.out.println("Disk is full!");
 			return -1;
 		}
 		//Create directory entry in parent directory for this new directory block
 		//Also open the directory to get the fd
 		if(_tfs_create_entry_dir(fdt_first_block_no[parent_fd], file_name, (byte)file_name.length,
 				(byte)0, newBlockNo, 0 ) > -1){
 			int fd = _tfs_open_fd(file_name, file_name.length, newBlockNo, fdt_first_block_no[parent_fd], 0, (byte)0);
 			//Update parent directories sizes
 			_tfs_update_dir_sizes(name, fdt_size[fd]);
 			return fd;
 		}
 		
		return -1;
	}


	//Function to delete a directory
	//Instructions said to only delete if it is an empty directory
	//However, this function will delete a directory that is not empty
	//by first deleting its contents, and then the directory itself
	public static int tfs_delete_dir(byte[] name, int nlength)
	{
		//Delete given directory
		//When deleting a directory we must delete all files/directories contained in directory
		int fd;
		//Open the directory
		fd = tfs_open(name, nlength);
		if(fd < 0)
		{
			System.out.println("Open file error! File does not exist!");
			return -1;
		}
		
		//Read the directory block into memory
		byte[] block = new byte[BLOCK_SIZE];
		_tfs_read_block(fdt_first_block_no[fd], block);
		//Get number of entries
		int no_entries;
		no_entries = _tfs_get_int_block(block, 0);
		
		//If number of entries is greater than 0 we need to delete all entries in directory
		if(no_entries > 0)
		{
			//Directory entry arrays
			byte[] is_directory = new byte[no_entries];
			byte[] namelength = new byte[no_entries];
			byte[][] names = new byte[no_entries][16];
			int[] first_block_no = new int[no_entries];
			int[] file_size = new int[no_entries];
			_tfs_read_directory_fd(fd, is_directory, namelength, names, first_block_no, file_size);
			
			byte[] slash = new byte[1];
			slash[0] = '/';
			String dir_path_s = new String(name);
			//Loop through the list of entries
			for(int i = 0; i < no_entries; i++)
			{
				//Call delete if entry is a file, call delete_dir if entry is a directory
				//To call these functions we need to send the full path
				//Build the full path
				//Get the entry name
				byte[] entry_name = new byte[16];
				entry_name = names[i];
				String entry_name_s = new String(entry_name);
				entry_name_s = entry_name_s.trim();
				String entry_path = dir_path_s + "/" + entry_name_s;
				//Now check if entry is a directory or a file
				if(is_directory[i] == 1)
					tfs_delete(entry_path.getBytes(), entry_path.getBytes().length);
				if(is_directory[i] == 0)
					tfs_delete_dir(entry_path.getBytes(), entry_path.getBytes().length);
			}
		}
		
		//We need to free all blocks allocated to the directory
		_tfs_return_blocks_fd(fd);
		
		//Update parent directories sizes
		_tfs_update_dir_sizes(name, fdt_size[fd] * -1);
		
		//Now we can delete the entry for the directory
		_tfs_delete_entry_dir(fdt_parent_block_no[fd], name, (byte)nlength);
		
		//Close file
 		_tfs_close_fd(fd);
		
		return 0;
	}

	//Read a directory and its contents into memory
	public static int tfs_read_dir(int fd,
		byte[] is_directory, byte[] nlength, byte[][] name, int[] first_block_no, int[] file_size)
	{
		if(fd < 0 || fd > FDT_SIZE-1)
 			return -1;

		//Check if directory
		if(!fdt_is_directory[fd])
		{
			System.out.println("File is not a directory!");
			return 0;
		}
		
 		// get the number of entries
 		int no_entries;
 		//Empty block for directory block to be read into
 		byte[] block = new byte[BLOCK_SIZE];
 		
 		//Read block located at first block # of directory into empty block
 		_tfs_read_block(fdt_first_block_no[fd], block);
 		no_entries = _tfs_get_int_block(block, 0);

 		// if there are no files or sub-directories, then return
 		if (no_entries == 0) 
 		{
 			System.out.println("Directory is empty!");
 			return 0;
 		}

 		// get the number of blocks allocated to the directory
 		int no_blocks;
 		if (no_entries == 0)
 			no_blocks = 1;
 		else {
 			no_blocks = no_entries / MAX_ENTRY_DIR;
 			if (no_entries % MAX_ENTRY_DIR != 0)
 				no_blocks++;
 		}

 		// read entries
 		int block_no = fdt_first_block_no[fd];
 		byte[] name_tmp = new byte[16];

 		for (int i = 0; i < no_entries; ) {
 			if (i % MAX_ENTRY_DIR == 0)
 				_tfs_read_block(block_no, block);
 			for (int j = 0; j < MAX_ENTRY_DIR && i < no_entries; j++, i++) {
 				is_directory[i] = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + IS_DIR);
 				name_tmp = _tfs_get_bytes_block(block, DIR_ENTRY_SIZE * j + NAME, 16);
 				for (int k = 0; k < 16; k++)
 					name[i][k] = name_tmp[k];
 				nlength[i] = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + NLENGTH);
 				reserved1 = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + RESERVED1);
 				reserved2 = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + RESERVED2);
 				first_block_no[i] = _tfs_get_int_block(block, DIR_ENTRY_SIZE * j + FBN);
 				file_size[i] = _tfs_get_int_block(block, DIR_ENTRY_SIZE * j + SIZE);
 			}
 		}

 		return no_entries;
 	}
	


	//--------------------------------------------------------------------------
	/*
	 * TFS private methods to handle in-memory structures
	 */


	//--------------------------------------------------------------------------
	/*
	 * DISK-related utilities
	 */

 	private static int _tfs_read_block(int block_no, byte buf[])
 	{
 		//System.out.println("Reading block" + block_no + " into memory!");
 		return TFSDiskInputOutput.tfs_dio_read_block(block_no, buf);
 	}


 	private static int _tfs_write_block(int block_no, byte buf[])
 	{
 		 //System.out.println("Writing block: " + block_no + "back to disk!");
 		return TFSDiskInputOutput.tfs_dio_write_block(block_no, buf);
 	}


 	//-------------------------------------------------------------------------
 	/*
 	 * FDT-related utilities
 	 */

 	private static int _tfs_open_fd(byte name[], int nlength, int first_block_no, int parent_block_no, int file_size, byte is_directory)
 	{
 		//Check if the file already open
 		int fd;
 		fd = tfs_check_fdt(first_block_no);
 		//Return fd if already open
 		if( fd > -1)
 			return fd;

 		// find a free entry
 		for (fd = 0; fd < FDT_SIZE; fd++)
 			if (fdt_is_free[fd])
 				break;
 		if (fd >= FDT_SIZE) {
 			System.out.println("Too many files open!");
 			return -1;
 		}
 		// update the entry

 		fdt_is_free[fd] = false;
 		fdt_name[fd] = name;
 		fdt_nlength[fd] = nlength;
 		fdt_file_pointer[fd] = 0;
 		fdt_first_block_no[fd] = first_block_no;
 		fdt_parent_block_no[fd] = parent_block_no;
 		fdt_size[fd] = file_size;
 		if( is_directory == 0)
 		{
 			fdt_is_directory[fd] = true;
 		}
 		else
 		{
 			fdt_is_directory[fd] = false;
 		}
 		
 		return fd;
 	}

 	//Move the file pointer to offset in file fd
 	private static int _tfs_seek_fd(int fd, int offset)
 	{
 		if(fd < 0 || fd > FDT_SIZE-1)
 			return -1;
 		if(offset == 0)
 			return fdt_file_pointer[fd];
 		if (offset < 0 || offset > fdt_size[fd]-1)
 			return -1;
 		
 		//Change file pointer
 		fdt_file_pointer[fd] = offset;
 		
 		//Update the entry
 		_tfs_update_entry_dir(fdt_parent_block_no[fd], fdt_name[fd], (byte)fdt_nlength[fd],
 				fdt_is_directory[fd], fdt_first_block_no[fd], fdt_size[fd]);
 		
 		return offset;
 	}

 	//Remove the file descriptor from the FDT
 	private static void _tfs_close_fd(int fd)
 	{
 		if(fd < 0 || fd > FDT_SIZE-1)
 			return;

 		// make the entry free
 		fdt_is_free[fd] = true;

 		// update the entry 
 		_tfs_update_entry_dir(fdt_parent_block_no[fd], fdt_name[fd], (byte)fdt_nlength[fd],
 				fdt_is_directory[fd], fdt_first_block_no[fd], fdt_size[fd]);
 	}

 	//Function to clear given fdt entry
 	private static void _tfs_free_fdt(int fd)
 	{
		fdt_is_free[fd] = true;
		fdt_first_block_no[fd] = -1;
		fdt_is_directory[fd] = false;
		Arrays.fill( fdt_name[fd], (byte) 0);
		fdt_size[fd] = 0;
		fdt_nlength[fd] = 0;
		fdt_parent_block_no[fd] = -1;
		fdt_file_pointer[fd] = 0;
 	}

 	private static int _tfs_read_bytes_fd(int fd, byte[] buf, int length)
 	{
 		if(fd < 0 || fd > FDT_SIZE-1)
 			return -1;
 		if (length <= 0)
 			return -1;

		// compute the actual length to read

	 	if (length > fdt_size[fd]-1 - fdt_file_pointer[fd] + 1)
 			length = fdt_size[fd]-1 - fdt_file_pointer[fd] + 1;

 		// compute the number of blocks that file uses from the file pointer

 		int no_blocks = 0;
 		int start, end;

 		start = fdt_file_pointer[fd] / BLOCK_SIZE;
 		end = (fdt_file_pointer[fd] + length - 1) / BLOCK_SIZE;
 		no_blocks = end - start + 1;

 		// read all blocks into an array for the length

 		int block_no;
		byte[] tmp = new byte[no_blocks * BLOCK_SIZE];
 		byte[] block = new byte[BLOCK_SIZE];

		block_no = _tfs_get_block_no_fd(fd, fdt_file_pointer[fd]);
 		for (int j = 0; j < no_blocks; j++) {
 			_tfs_read_block(block_no, block);
 			for (int i = 0; i < BLOCK_SIZE; i++)
 				tmp[j * BLOCK_SIZE + i] = block[i];
 			block_no = fat[block_no];
 		}

 		// take out length bytes from the above array, and return

 		int displacement = fdt_file_pointer[fd] % BLOCK_SIZE;
 		for (int i = 0; i < length; i++)
 			buf[i] = tmp[displacement + i];
 		

 		return length;
 	}


 	private static int _tfs_write_bytes_fd(int fd, byte[] buf, int length)
 	{
 		if(fd < 0 || fd > FDT_SIZE-1)
 			return -1;

 		if (length <= 0)
 			return -1;

 		// compute the number of blocks to be overwritten

 		int no_blocks = 0;
 		int start, end;

 		start = fdt_file_pointer[fd] / BLOCK_SIZE;
 		end = (fdt_file_pointer[fd] + length - 1) / BLOCK_SIZE;
 		no_blocks = end - start + 1;
 		// compute the number of blocks that the file uses from the current file pointer

 		int no_use_blocks = 0;

 		start = fdt_file_pointer[fd] / BLOCK_SIZE;
 		end = (fdt_size[fd] - 1) / BLOCK_SIZE;
 		no_use_blocks = end - start + 1;

 		// if more blocks are needed, then attach them

 		int block_no;

 		for (int i = 0; i < no_blocks - no_use_blocks; i++) {
 			block_no = _tfs_get_block_fat();
 			if (block_no < 0)
 				return -1;  // no more space
 			_tfs_attach_block_fat(fdt_first_block_no[fd], block_no);
 		}

 		// save old data into an array of blocks<------------------------------------------------------------

		byte[] tmp = new byte[no_blocks * BLOCK_SIZE];
		byte[] block = new byte[BLOCK_SIZE];
 		block_no = _tfs_get_block_no_fd(fd, fdt_file_pointer[fd]);
 		for (int i = 0; i < no_use_blocks; i++) {
 			_tfs_read_block(block_no, block);
 			for (int j = 0; j < BLOCK_SIZE; j++)
 				tmp[i * BLOCK_SIZE + j] = block[j];
 			block_no = fat[block_no];
 		}

 		// overwrite new data into the array of blocks

 		int displacement = fdt_file_pointer[fd] % BLOCK_SIZE;

 		for (int i = 0; i < length; i++)
 			tmp[displacement + i] = buf[i];

 		// write the blocks back into the disk

 		block_no = _tfs_get_block_no_fd(fd, fdt_file_pointer[fd]);
 		for (int i = 0; i < no_blocks; i++) {
 			for (int j = 0; j < BLOCK_SIZE; j++)
 				block[j] = tmp[i * BLOCK_SIZE + j];
 			_tfs_write_block(block_no, block);
 			block_no = fat[block_no];
 		}
 		
		//Update file size
		fdt_size[fd] += length;
		//Update entry
		_tfs_update_entry_dir(fdt_parent_block_no[fd], fdt_name[fd], (byte)fdt_nlength[fd],
				fdt_is_directory[fd], fdt_first_block_no[fd], fdt_size[fd]);
 		// return # of bytes written
 		return length;
 	}
 	
 	

 	//Get block number of a file given fd and offset
 	private static int _tfs_get_block_no_fd(int fd, int offset)
 	{
 		if(fd < 0 || fd > FDT_SIZE-1)
 			return -1;
 		if(offset == 0)
 			return fdt_first_block_no[fd];
 		if (offset < 0 || offset > fdt_size[fd]-1)
 			return -1;
 		
 		int block_no = fdt_first_block_no[fd];
 		for (int i = 0; i < offset/BLOCK_SIZE; i++)
 			block_no = fat[block_no];

 		return block_no;
 	}
 	
 	//Function to extract the filename from a full path
 	private static byte[] _tfs_extract_filename(byte[] full_path)
 	{
		String full_path_s = new String(full_path);
		String file_name = "";
 		StringTokenizer st = new StringTokenizer(full_path_s, "/");
 		int token_count = st.countTokens();
 		if(token_count > 1)
 		{
 			//Get the last token to extract filename from path
			for(int i = 0; i < token_count; i++) 
			{
				file_name = st.nextToken();
			}
 		}else 
 			{
 				file_name = st.nextToken();
 			}
 		return file_name.getBytes();
 	}
 	
 	//Function to extract the parent path from a full path
 	public static byte[] get_parent_path(byte[] full_path)
 	{
 		String full = new String(full_path);
 		StringTokenizer st = new StringTokenizer(full, "/");
 		int token_count = st.countTokens();
 		String parent_path = "/";
 		for(int i = 0; i < token_count - 1; i++)
 			parent_path += st.nextToken() + "/";
 		return parent_path.getBytes();
 	}
 	
 	//Get the entry number and block number of entry_name in directory starting at block_no
 	private static int _tfs_get_entry_location(int first_block_no, byte[] name, int[] entry_block_no)
 	{
 		
 		byte[] block = new byte[BLOCK_SIZE];
 		int entry_no;
 		//Ensure name is only filename
 		byte[] file_name = _tfs_extract_filename(name);
 		//Loop through blocks using the FAT - looking for 'name'
 		do 
 		{	
 			//Read block into memory
 			_tfs_read_block(first_block_no, block);
 			//Check for 'name' in block by grabbing 15 bytes at each name offset
 			//in a block
 			byte[] entry_name = new byte[15];
 			String sn = new String(file_name);
 			sn = sn.trim();
 			for(int i = 5, j = 0; i < 90; i += DIR_ENTRY_SIZE, j++) {
 				entry_name =_tfs_get_bytes_block(block, i, 15);
 				String s = new String(entry_name);
 				s = s.trim();
 				if(s.equals(sn))
 				{
 					entry_block_no[0] = first_block_no;
 					return entry_no = j;
 				}
 			}
 			first_block_no = fat[first_block_no];
 			if(first_block_no == -1)
 				break;	
 		}while(true);
 		return -1;
 	}

 	//Read contents of directory
 	private static int _tfs_read_directory_fd(int fd, byte[] is_directory,
 		byte[] nlength, byte[][] name, int[] first_block_no, int[] file_size)
 	{
 		if(fd < 0 || fd > FDT_SIZE-1)
 			return -1;

 		// get the number of entries

 		int no_entries;
 		//Empty block for directory block to be read into
 		byte[] block = new byte[BLOCK_SIZE];
 		
 		//Read block located at first block # of directory into empty block
 		_tfs_read_block(fdt_first_block_no[fd], block);
 		no_entries = _tfs_get_int_block(block, 0);

 		// if there are no files or sub-directories, then return

 		if (no_entries == 0)
 			return 0;

 		// get the number of blocks allocated to the directory

 		int no_blocks;
 		if (no_entries == 0)
 			no_blocks = 1;
 		else {
 			no_blocks = no_entries / MAX_ENTRY_DIR;
 			if (no_entries % MAX_ENTRY_DIR != 0)
 				no_blocks++;
 		}

 		// read entries

 		int block_no = fdt_first_block_no[fd];
 		byte[] name_tmp = new byte[16];

 		for (int i = 0; i < no_entries; ) {
 			if (i % MAX_ENTRY_DIR == 0)
 				_tfs_read_block(block_no, block);
 			for (int j = 0; j < MAX_ENTRY_DIR && i < no_entries; j++, i++) {
 				is_directory[i] = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + IS_DIR);
 				name_tmp = _tfs_get_bytes_block(block, DIR_ENTRY_SIZE * j + NAME, 16);
 				for (int k = 0; k < 16; k++)
 					name[i][k] = name_tmp[k];
 				nlength[i] = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + NLENGTH);
 				reserved1 = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + RESERVED1);
 				reserved2 = _tfs_get_byte_block(block, DIR_ENTRY_SIZE * j + RESERVED2);
 				first_block_no[i] = _tfs_get_int_block(block, DIR_ENTRY_SIZE * j + FBN);
 				file_size[i] = _tfs_get_int_block(block, DIR_ENTRY_SIZE * j + SIZE);
 			}
 		}

 		return no_entries;
 	}


 	//--------------------------------------------------------------------------
 	/*
 	 * PCB-related utilities
 	 */


 	/*
 	 * write PCB back into the disk
 	 */

 	private static void _tfs_write_pcb()
 	{
 		int[] ablock = new int[BLOCK_SIZE / 4];
 		ablock[0] = pcb_size_fs;
 		ablock[1] = pcb_size_fat;
 		ablock[2] = pcb_pointer_free;
 		ablock[3] = pcb_pointer_root;
 		ablock[4] = pcb_magic;

 		ByteBuffer bbuf = ByteBuffer.allocate(ablock.length * 4);
 		bbuf = bbuf.putInt(ablock[0]);
 		bbuf = bbuf.putInt(ablock[1]);
 		bbuf = bbuf.putInt(ablock[2]);
 		bbuf = bbuf.putInt(ablock[3]);
 		bbuf = bbuf.putInt(ablock[4]);

 		TFSDiskInputOutput.tfs_dio_write_block(1, bbuf.array());

 		return;
 	}


 	/*
 	 * read PCB from the disk
 	 */

 	private static void _tfs_read_pcb()
 	{
 		byte[] bblock = new byte[BLOCK_SIZE];

 		TFSDiskInputOutput.tfs_dio_read_block(1, bblock);

 		ByteBuffer bbuf = ByteBuffer.wrap(bblock);

 		pcb_size_fs = bbuf.getInt();
 		pcb_size_fat = bbuf.getInt();
 		pcb_pointer_free = bbuf.getInt();
 		pcb_pointer_root = bbuf.getInt();
 		pcb_magic = bbuf.getInt();

 		return;
 	}


 	//--------------------------------------------------------------------------
 	/*
 	 * FAT-related utilities
 	 */


 	/*
 	 * write FAT back into the disk
 	 */

 	private static void _tfs_write_fat()
 	{
		ByteBuffer bbuf = ByteBuffer.allocate(BLOCK_SIZE);
		int j = 0;

 		for (int i = 0; i < fat.length; i++) {
 			bbuf = bbuf.putInt(fat[i]);
 			if (i % (BLOCK_SIZE/4) == (BLOCK_SIZE/4 - 1)) {
 				TFSDiskInputOutput.tfs_dio_write_block(2 + j, bbuf.array());
 				j++;
	 			bbuf.rewind();
 			}
 		}

 		return;
 	}


 	/*
 	 * read FAT from the disk
 	 */

 	private static void _tfs_read_fat()
 	{
		ByteBuffer bbuf = null;
		byte[] block = new byte[BLOCK_SIZE];
		int j = 0;

 		for (int i = 0; i < fat.length; i++) {
 			if ((i * 4) % BLOCK_SIZE == 0) {
 				TFSDiskInputOutput.tfs_dio_read_block(2 + j, block);
 				j++;
 				bbuf = ByteBuffer.wrap(block);
 			}
 			fat[i] = bbuf.getInt();
 		}

 		return;
 	}


 	/*
 	 * get a free block
 	 */

 	private static int _tfs_get_block_fat()
 	{
 		// if there is no free block, then return error

 		if (pcb_pointer_free < 0)
 			return -1;

 		// there is a free block

 		int block_no;
 		
 		//Get the block # from the pointer to the first free block
 		block_no = pcb_pointer_free;
 		//Set pcb_pointer_free to the next block;
 		pcb_pointer_free = fat[pcb_pointer_free];
 		//Set FAT value at block_no to -1
 		fat[block_no] = -1;
 		return block_no;
 	}


 	/*
 	 * return a free block
 	 */

 	private static void _tfs_return_block_fat(int block_no)
 	{
 		// if out of bound, then return

 		if (block_no >= pcb_size_fs || block_no < pcb_size_fat + 2)
 			return;

 		// return a block into the free block list

 		fat[block_no] = pcb_pointer_free;
 		pcb_pointer_free = block_no;
 		
 		// Just for good measure clear the block (not really necessary but lets do it anyhow)
 		byte[] empty_block = new byte[BLOCK_SIZE];
 		_tfs_write_block(block_no, empty_block);
 	}
 	
 	/*
 	 * Return all blocks associated with a file
 	 */
 	
 	private static void _tfs_return_blocks_fd(int fd)
 	{
		//Release all blocks allocated to entry
		//Get the number of blocks allocated to this file
		int no_blocks_used;
		
		//If the size of the file is less than one block size we can release
		//it's first block no
		if(fdt_size[fd] < BLOCK_SIZE) 
		{
			_tfs_return_block_fat(fdt_first_block_no[fd]);
			return;
		}
		no_blocks_used = fdt_size[fd] / BLOCK_SIZE;
		if(fdt_size[fd] % BLOCK_SIZE != 0)
			no_blocks_used++;
		//Get the block numbers of the allocated blocks
		int[] block_nums = new int[no_blocks_used];
		int index = 0;
		int block_no = fdt_first_block_no[fd];
		//Use the FAT to get all block numbers
		do
		{
			block_nums[index] = block_no;
			block_no = fat[block_no];
			index++;
		}while(fat[block_no] != -1 );
		
		//Return all blocks to the free block list
		for(index = 0; index < no_blocks_used; index++)
			_tfs_return_block_fat(block_nums[index]);
 	}

 	/*
 	 * attach a block at the end of a file having start_block_no
 	 */

 	private static int _tfs_attach_block_fat(int start_block_no, int new_block_no)
 	{
 		// if out of bound, then return

 		if (new_block_no >= pcb_size_fs || new_block_no < pcb_size_fat + 2)
 			return -1;
 		if (start_block_no >= pcb_size_fs || start_block_no < pcb_size_fat + 2)
 			return -1;

 		// find the last block

 		int last_block_no = start_block_no;

 		while (fat[last_block_no] > 0) {
 			last_block_no = fat[last_block_no];
 		}

 		// attach the new block

 		fat[last_block_no] = new_block_no;
 		fat[new_block_no] = -1;

 		return new_block_no;
 	}


 	//--------------------------------------------------------------------------
 	/*
 	 * directory handling routines
 	 */

 	//Returns first block_no of parent directory that entry 'name' exists in
 	private static int _tfs_search_dir(byte[] name, int nlength)
 	{
 		if (name[0] != '/')
 			return -1;
 		
 		if(name[0] == '/' && nlength == 1)
 			return pcb_pointer_root;

 		// prepare tokenizing

 		String s = new String(name);
 		s = s.trim();
 		StringTokenizer st = new StringTokenizer(s, "/");
 		int no_of_tokens = st.countTokens();
 		// take out directories or file names, and search in depth

 		String n;
 		int block_no;
 		byte[] is_directory = new byte[1];
 		int[] fbn = new int[1];
 		int[] size = new int[1];
 		byte[] bn;
 		byte bnlen;

 		block_no = pcb_pointer_root;
 		
 		for (int i = 0; i < no_of_tokens; i++)
 		{
 			n = st.nextToken();
 			bn = n.getBytes();
 			bnlen = (byte)(bn.length);
 			if (_tfs_get_entry_dir(block_no, bn, bnlen, is_directory, fbn, size) < 0)
 				return -1;
 			if (st.hasMoreTokens() && is_directory[0] != 0)
 				return -1;
 			if(st.hasMoreTokens())
 				block_no = fbn[0];
 		}
 		return block_no;
 	}

 	//Function to get entry information given name from a directory whose first block
 	//number is block_no
 	private static int _tfs_get_entry_dir(int block_no, byte[] name, byte nlength,
 		byte[] is_directory, int[] fbn, int[] size)
 	{
 		//Get the entry for name from the directory of which the first block number is block_no
 		//Get entry location
 		int[] entry_block_no = new int[1];
 		String s = new String(name);
 		int entry_no = _tfs_get_entry_location(block_no, name, entry_block_no);

 		if(entry_no == -1)
 			return -1;
 		
 		//Read the block into memory
 		byte[] block = new byte[BLOCK_SIZE];
 		_tfs_read_block(entry_block_no[0], block);
 		//Save info into arrays
 		//Get is_directory
 		is_directory[0] = _tfs_get_byte_block(block, entry_no * DIR_ENTRY_SIZE + IS_DIR);
 		//Get first block number
 		fbn[0] = _tfs_get_int_block(block, entry_no * DIR_ENTRY_SIZE + FBN);
 		//Get size
 		size[0] = _tfs_get_int_block(block, entry_no * DIR_ENTRY_SIZE + SIZE);
 
 		return 0;
 	}

	//Create an entry for name in the directory of which the first block number is block_no
	//name is not a full path
	//The whole size of the directory might be changed
 	private static int _tfs_create_entry_dir(int parent_block_no, byte[] name, byte nlength,
 		byte is_directory, int fbn, int size)
 	{
 		
 		int parentBlockNo = parent_block_no;
 		//Read the first parent block into memory
 		byte[] block = new byte[BLOCK_SIZE];
 		_tfs_read_block(parent_block_no, block);
 		
 		//Determine how many entries are in the block
 		//We will use this to increase the number of entries in this directory block by 1
 		//as well as find out where we need to write this entry
 		int entries_no = _tfs_get_int_block(block, 0);

 		//If there are more than 4 entries in the block we need to use the FAT
 		//to find the block number of the next block allocated to the directory until we reach the
 		//last block allocated
 		if(entries_no > 4) {
 			while(fat[parent_block_no] != -1)
 				parent_block_no = fat[parent_block_no];
 		}
 		//if entries_no % 4 is 0 then we need to allocate a new block to the directory
 		//and update the FAT to indicate this
 		if(entries_no % 4 == 0 && entries_no != 0) {
 			System.out.println("Block is full! Allocating new block to directory!");
 			int new_block;
 			new_block = _tfs_get_block_fat();
 			//If get_block returned -1 then the disk is full
 			if(new_block < 0) {
 				System.out.println("Disk is full! Cannot allocate a new block. Entry cannot be created!");
 				return -1;
 			}
 			//Attach the new block to the directory by updating the FAT
 			_tfs_attach_block_fat(parent_block_no, new_block);
 			//This block number is now where we will be writing the entry into
 			parent_block_no = new_block; 		
 			//Read this block into memory for manipulation
 			_tfs_read_block(parent_block_no, block);
 			System.out.println("Block: " + parent_block_no + " has been read into memory!");
 		}

 		//At this point parent_block_no will be a proper place to write this entry to
 		//We just need to determine where in the block we are going to write it
 		//We use entries_no to determine this
 		
 		//Each entry will be 28 bytes
 		//If directory has 2 entries then we will write the information in the
 		//3rd "slot" of the directory block block_no * block_size + ( 2 * dir_entry_size ) or whatever
 		//If the directory has more than 4 entries then we need to check the FAT to find
 		//out where the next part of the directory is FAT[block_no] = block_no of next part of directory
 		//We need to actually loop through the FAT until we find -1 or EOF and then check that block to determine
 		//how many entries are in it, if there are 4 entries we need to allocate another block for the directory
 		//and write the new directory entry into that block
 		

 				
		//Write entry info to block in correct entry position
		//Set # of entries equal to entries_no + 1 because we are adding a new entry
		_tfs_put_int_block(block, 0, entries_no + 1);
		//Set parent block # to block # of parent directory
		_tfs_put_int_block(block, (entries_no % MAX_ENTRY_DIR) * DIR_ENTRY_SIZE + PARENTBN, parent_block_no);
		//Set is_directory to is_directory to label this as either a file or a sub-directory
		_tfs_put_byte_block(block, (entries_no % MAX_ENTRY_DIR) * DIR_ENTRY_SIZE + IS_DIR, is_directory);
		//Set entry name to name
		_tfs_put_bytes_block(block, (entries_no % MAX_ENTRY_DIR) * DIR_ENTRY_SIZE + NAME, name, nlength);
		//Set nlength equal to the length of the desired name
		_tfs_put_byte_block(block, (entries_no % MAX_ENTRY_DIR) * DIR_ENTRY_SIZE + NLENGTH, (byte)nlength);
		//Set first block # to block # that this directory entry points to
		//(where it's subsequent entries or data will be stored)
		_tfs_put_int_block(block, (entries_no % MAX_ENTRY_DIR) * DIR_ENTRY_SIZE + FBN, fbn);
		//Set size in entry to size
		_tfs_put_int_block(block, (entries_no % MAX_ENTRY_DIR) * DIR_ENTRY_SIZE + SIZE, size);
		//Write the block back to the file
		_tfs_write_block(parentBlockNo, block);

		return 0;
 	}

 	//Delete entry with 'name' in directory that begins in 'block_no'
 	//name is not full path
 	private static void _tfs_delete_entry_dir(int block_no, byte[] name, byte nlength)
 	{
 		//Byte arrays
 		byte[] empty_entry = new byte[DIR_ENTRY_SIZE];
 		byte[] empty_block = new byte[BLOCK_SIZE];
 		byte[] last_entry = new byte[DIR_ENTRY_SIZE];
 		byte[] block = new byte[BLOCK_SIZE];
 		
 		//Read the first parent block into memory
 		_tfs_read_block(block_no, block);		
 		//find out how many entries are in directory
 		int no_entries = _tfs_get_int_block(block, 0);
 		//Update the no_entries to no_entries - 1
 		_tfs_put_int_block(block, 0, no_entries - 1);
 		//Write parent block back to disk
 		_tfs_write_block(block_no, block);
 		
 		//Check to see if this is the only entry in the directory
 		//If so, we can simply write an empty block back to the disk
 		if(no_entries == 1) 
 		{
 			System.out.print("Deleting last entry in directory!");
 			_tfs_write_block(block_no, empty_block);
 			System.out.println("Directory is now empty!");
 			return;
 		}
 		
 		//Find where the entry we want to delete is located
 		int[] entry_block_no = new int[1];
 		int del_entry_no = _tfs_get_entry_location(block_no, name, entry_block_no);

 		//Get the block number that holds the last entry
 		//If no_entries is less than or equal to MAX_ENTRY_DIR then this will be the
 		//same as the first allocated block number (block_no)
 		int last_block_no = block_no;
 		if(no_entries > MAX_ENTRY_DIR)
 			while(fat[last_block_no] != -1)
 				last_block_no = fat[block_no];
 		
 		if(last_block_no == entry_block_no[0])
 		{
 			if(no_entries > 4 && no_entries % MAX_ENTRY_DIR == 1)
 			{
 				_tfs_write_block(last_block_no, empty_block);
 				_tfs_return_block_fat(last_block_no);
 			}
 				
 			_tfs_read_block(last_block_no, block);
 			last_entry = _tfs_get_bytes_block(block, (no_entries - 1) % MAX_ENTRY_DIR * DIR_ENTRY_SIZE, DIR_ENTRY_SIZE);
 			_tfs_put_bytes_block(block, del_entry_no * DIR_ENTRY_SIZE, last_entry, last_entry.length);
 			_tfs_put_bytes_block(block, (no_entries - 1) % MAX_ENTRY_DIR * DIR_ENTRY_SIZE, empty_entry, empty_entry.length);
 			_tfs_write_block(last_block_no, block);
 		}
 		
 		
 		//Entries are in different blocks
	 	//Read block with last entry into memory
	 	_tfs_read_block(last_block_no, block);
 		//Get last entry
	 	last_entry = _tfs_get_bytes_block(block, (no_entries - 1) % MAX_ENTRY_DIR * DIR_ENTRY_SIZE, last_entry.length);
 		//Read block with entry to be deleted into memory
	 	byte[] del_entry_block = new byte[BLOCK_SIZE];
	 	_tfs_read_block(entry_block_no[0], del_entry_block);
	 	//overwrite del_entry with last_entry and write block back to disk <--this is so entry cannot be found again
 		_tfs_put_bytes_block(del_entry_block, del_entry_no % MAX_ENTRY_DIR * DIR_ENTRY_SIZE, last_entry, last_entry.length);
 		//Write block back to disk
 		_tfs_write_block(entry_block_no[0], del_entry_block);
 		//Overwrite last_entry with empty_entry
 		_tfs_put_bytes_block(block, (no_entries - 1) % MAX_ENTRY_DIR * DIR_ENTRY_SIZE, empty_entry, empty_entry.length);
 		//If this is the last entry in this block we can return this block to the
 		//free block list
 		if(no_entries > MAX_ENTRY_DIR && no_entries % MAX_ENTRY_DIR == 1)
 			_tfs_return_block_fat(last_block_no);
 		//Write block back to disk
 		_tfs_write_block(last_block_no, block);
 		
 	}
 	
 	//Will update all parent directory entries sizes given
 	//Name is full path of file/dir to be deleted/created
 	//size_of_change is negative for a deletion and positive for a creation
 	private static int _tfs_update_dir_sizes(byte[] name, int size_of_change)
 	{
 		//name is a full path to the file/directory that is being deleted/created/modified
 		//We need to open each parent directory and update the size
 		
 		//Split the full path and open directories saving the fd into an array
 		if (name[0] != '/')
 			return -1;
 		String s = new String(name);
 		s = s.trim();
 		int fd;
 		//Check if root
 		String slash = "/";
 		if(s.equals(slash))
 		{
 			fd = tfs_open(name, name.length);
 			fdt_size[fd] += size_of_change;
 			 _tfs_update_entry_dir(fdt_parent_block_no[fd], fdt_name[fd], (byte)fdt_nlength[fd],
					 fdt_is_directory[fd], fdt_first_block_no[fd], fdt_size[fd]);
 			 return 0;
 		}
 		
 		//Update all parent directories
 		StringTokenizer st = new StringTokenizer(s, "/");
 		int token_count = st.countTokens();
 		
		//Get all but last token to extract path to parent directory
		for(int j = 1; j < token_count; j++) {
			StringTokenizer sx = new StringTokenizer(s, "/");
			String parent_dir_path = "";
			for(int i = 0; i < token_count - j; i++) 
			{
				parent_dir_path = "/";
				parent_dir_path += sx.nextToken();
			}
			
 			//Open the directory
 			fd = tfs_open(parent_dir_path.getBytes(), parent_dir_path.getBytes().length);
 			//Update FDT entry
 			fdt_size[fd] += size_of_change;
 			//Update directory entry
 			_tfs_update_entry_dir(fdt_parent_block_no[fd], fdt_name[fd], (byte)fdt_nlength[fd],
					 fdt_is_directory[fd], fdt_first_block_no[fd], fdt_size[fd]);
		}
		//Now open root and update size
 		byte[] root = slash.getBytes();
 		fd = tfs_open(root, root.length);
			fdt_size[fd] += size_of_change;
			_tfs_update_entry_dir(fdt_parent_block_no[fd], fdt_name[fd], (byte)fdt_nlength[fd],
				 fdt_is_directory[fd], fdt_first_block_no[fd], fdt_size[fd]);
 		return 0;

 	}

 	private static void _tfs_update_entry_dir(int parent_block_no, byte[] name, byte nlength,
 		boolean is_directory, int fbn, int size)
 	{
 		//Update the entry for name in the directory of which the first block number
 		//is block_no
 		
 		//Find the entry location
 		int entry_no;
 		int[] entry_block_no = new int[1];
 		entry_no = _tfs_get_entry_location(parent_block_no, name, entry_block_no);

 		if(entry_no == -1)
 			return;
 		
 		//Read the block that the entry is in into memory
 		byte[] block = new byte[BLOCK_SIZE];
 		_tfs_read_block(entry_block_no[0], block);
 		
 		//Update the entry with the supplied information
 		//Write entry info to block in correct entry position
 		//Update parent_block_no
 		_tfs_put_int_block(block, entry_no * DIR_ENTRY_SIZE + PARENTBN, parent_block_no);
		//Set is_directory byte in entry to is_directory
		_tfs_put_byte_block(block, entry_no * DIR_ENTRY_SIZE + IS_DIR, (byte)(is_directory?0:1));
		//Set name to name
		_tfs_put_bytes_block(block, entry_no * DIR_ENTRY_SIZE + NAME, name, name.length);
		//Set first block # to block # that this directory entry points to
		_tfs_put_int_block(block, entry_no * DIR_ENTRY_SIZE + FBN, fbn);
		//Set size int in entry to size
		_tfs_put_int_block(block, entry_no * DIR_ENTRY_SIZE + SIZE, size);
		//Write the block back to the file
		_tfs_write_block(entry_block_no[0], block);
 	}


 	//--------------------------------------------------------------------------
 	/*
 	 * block handling utilities
 	 */


 	/*
 	 * get an int from a block
 	 */

 	private static int _tfs_get_int_block(byte[] block, int offset)
 	{	
 		byte[] int_bytes = new byte[2];
 		int_bytes = _tfs_get_bytes_block(block, offset, 2);
		int x = (0 << 24) | (0 << 16)
	            | ((int_bytes[1] & 0xFF) << 8) | ((int_bytes[0] & 0xFF) << 0);
		return x;
 	}


 	/*
 	 * put an int into a block
 	 */

 	private static void _tfs_put_int_block(byte[] block, int offset, int data)
 	{
		byte[] byte_int = new byte[2];
		byte_int[0] = (byte) (data & 0xFF);
		byte_int[1] = (byte) ((data >> 8) & 0xFF);
		_tfs_put_bytes_block(block, offset, byte_int, 2);
 	}


	/*
	 * get a byte from a block
	 */

 	private static byte _tfs_get_byte_block(byte[] block, int offset)
 	{
 		return block[offset];
 	}


 	/*
 	 * put a byte into a block
 	 */

 	private static void _tfs_put_byte_block(byte[] block, int offset, byte data)
 	{
 			block[offset] = data;
 	}


	/*
	 * get bytes from a block
	 */

 	private static byte[] _tfs_get_bytes_block(byte[] block, int offset, int length)
 	{
 		byte[] buf = new byte[length];

 		for (int i = 0; i < length; i++)
 			buf[i] = block[i + offset];

 		return buf;
 	}


	/*
	 * put bytes into a block
	 */

 	private static void _tfs_put_bytes_block(byte[] block, int offset, byte[] buf, int length)
 	{
 		for (int i = 0; i < length; i++)
 			block[i + offset] = buf[i];
 	}
}