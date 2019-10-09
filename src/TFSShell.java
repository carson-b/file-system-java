import java.io.*;
import java.util.*;
@SuppressWarnings("unused")

public class TFSShell extends Thread
{
	public TFSShell()
	{
	}

	public void run()
	{
		readCmdLine();
	}

	/*
	 * User interface routine
	 */

	void readCmdLine()
	{
		String line, cmd, arg1, arg2, arg3, arg4;
		StringTokenizer stokenizer;
		@SuppressWarnings("resource")
		Scanner scanner = new Scanner(System.in);

		System.out.println("Hal: Good morning, Dave!\n");

		while(true) {

			System.out.print("ush> ");

			line = scanner.nextLine();
			line = line.trim();
			stokenizer = new StringTokenizer(line);
			if (stokenizer.hasMoreTokens()) {
				cmd = stokenizer.nextToken();

				if (cmd.equals("mkfs"))
					mkfs();
				else if (cmd.equals("mount"))
					mount();
				else if (cmd.equals("umount"))
					umount();
				else if (cmd.equals("sync"))
					sync();
				else if (cmd.equals("prrfs"))
					prrfs();
				else if (cmd.equals("prmfs"))
					prmfs();

				else if (cmd.equals("mkdir")) {
					if (stokenizer.hasMoreTokens()) {
						arg1 = stokenizer.nextToken();
						mkdir(arg1);
					}
					else
						System.out.println("Usage: mkdir directory");
				}
				else if (cmd.equals("rmdir")) {
					if (stokenizer.hasMoreTokens()) {
						arg1 = stokenizer.nextToken();
						rmdir(arg1);
					}
					else
						System.out.println("Usage: rmdir directory");
				}
				else if (cmd.equals("ls")) {
					if (stokenizer.hasMoreTokens()) {
						arg1 = stokenizer.nextToken();
						ls(arg1);
					}
					else
						System.out.println("Usage: ls directory");
				}
				else if (cmd.equals("create")) {
					if (stokenizer.hasMoreTokens()) {
						arg1 = stokenizer.nextToken();
						create(arg1);
					}
					else
						System.out.println("Usage: create file");
				}
				else if (cmd.equals("rm")) {
					if (stokenizer.hasMoreTokens()) {
						arg1 = stokenizer.nextToken();
						rm(arg1);
					}
					else
						System.out.println("Usage: rm file");
				}
				else if (cmd.equals("print")) {
					if (stokenizer.hasMoreTokens())
						arg1 = stokenizer.nextToken();
					else {
						System.out.println("Usage: print file position number");
						continue;
					}
					if (stokenizer.hasMoreTokens())
						arg2 = stokenizer.nextToken();
					else {
						System.out.println("Usage: print file position number");
						continue;
					}
					if (stokenizer.hasMoreTokens())
						arg3 = stokenizer.nextToken();
					else {
						System.out.println("Usage: print file position number");
						continue;
					}
					try {
						print(arg1, Integer.parseInt(arg2), Integer.parseInt(arg3));
					} catch (NumberFormatException nfe) {
						System.out.println("Usage: print file position number");
					}
				}
				else if (cmd.equals("append")) {
					if (stokenizer.hasMoreTokens())
						arg1 = stokenizer.nextToken();
					else {
						System.out.println("Usage: append file number");
						continue;
					}
					if (stokenizer.hasMoreTokens())
						arg2 = stokenizer.nextToken();
					else {
						System.out.println("Usage: append file number");
						continue;
					}
					try {
						append(arg1, Integer.parseInt(arg2));
					} catch (NumberFormatException nfe) {
						System.out.println("Usage: append file number");
					}
				}
				else if (cmd.equals("cp")) {
					if (stokenizer.hasMoreTokens())
						arg1 = stokenizer.nextToken();
					else {
						System.out.println("Usage: cp file directory");
						continue;
					}
					if (stokenizer.hasMoreTokens())
						arg2 = stokenizer.nextToken();
					else {
						System.out.println("Usage: cp file directory");
						continue;
					}
					cp(arg1, arg2);
				}
				else if (cmd.equals("rename")) {
					if (stokenizer.hasMoreTokens())
						arg1 = stokenizer.nextToken();
					else {
						System.out.println("Usage: rename src_file dest_file");
						continue;
					}
					if (stokenizer.hasMoreTokens())
						arg2 = stokenizer.nextToken();
					else {
						System.out.println("Usage: rename src_file dest_file");
						continue;
					}
					rename(arg1, arg2);
				}

				else if (cmd.equals("exit")) {
					exit();
					System.out.println("\nHal: Good bye, Dave!\n");
					break;
				}

				else
					System.out.println("-ush: " + cmd + ": command not found");
			}
		}


	}


/*
 * You need to implement these commands
 */

	void mkfs()
	{
		int r;

		r = TFSFileSystem.tfs_mkfs();

		if (r == -1)
			System.out.println("mkfs: cannot make file system");

		return;
	}

	//Mount the FS into memory
	void mount()
	{
		if (TFSFileSystem.tfs_mount() < 0)
			System.out.println("Cannot mount FS");

		return;
	}
	
	//Write the FS in memory onto disk
	void umount()
	{
		if (TFSFileSystem.tfs_umount() < 0)
			System.out.println("Cannot unmount FS");

		return;
	}

	void sync()
	{
		//Sync FS, will return -1  if system cannot be synced
		if (TFSFileSystem.tfs_sync() < 0)
			System.out.println("Cannot sync FS");

		return;
	}

	void prrfs()
	{
		String msg = TFSFileSystem.tfs_prrfs();
		if (msg == null)
			System.out.println("Cannot read raw FS");
		else
			System.out.print(TFSFileSystem.tfs_prrfs());

		return;
	}

	void prmfs()
	{
		String msg = TFSFileSystem.tfs_prmfs();
		if (msg == null)
			System.out.println("Cannot read mounted FS");
		else
			System.out.print(TFSFileSystem.tfs_prmfs());

		return;
	}
	
	//Make a directory
	void mkdir(String directory)
	{
		//Create directory
		if(TFSFileSystem.tfs_create_dir(directory.getBytes(), directory.getBytes().length) > 0)
			System.out.println("Directory created!");
		else
			System.out.println("Directory not created!");
		return;
	}
	
	//Remove a directory
	void rmdir(String directory)
	{
		//Remove the directory
		if(TFSFileSystem.tfs_delete_dir(directory.getBytes(), directory.getBytes().length) > -1)
			System.out.println("Directory removed!");
		else
			System.out.println("Directory not removed!");
	}
	
	//List contents of directory
	void ls(String directory)
	{
		//Open the directory
		int fd = TFSFileSystem.tfs_open(directory.getBytes(), directory.getBytes().length);
		if(fd < 0)
		{
			System.out.println("Directory does not exist!");
			return;
		}
		
		//Get the number of entries in the directory
		int max_files = TFSFileSystem.tfs_get_no_entries(fd);
		if(max_files == 0)
		{
			System.out.println("Directory is empty!");
			return;
		}
		System.out.println("Directory has: " + max_files + " entries.");
		
		byte[] is_directory = new byte[max_files];
		byte[] nlength = new byte[max_files];
		byte[][] names = new byte[max_files][16];
		int[] first_block_no = new int[max_files];
		int[] file_size = new int[max_files];
		
		//Use the fd to read the directory and get the contents
		TFSFileSystem.tfs_read_dir(fd, is_directory, nlength, names, first_block_no, file_size);
		//print the results
		for(int i = 0; i < max_files; i++) {
			
			String s = new String(names[i]);
			System.out.print(s + "               " + file_size[i]);
			System.out.println("");
		}
	}

	//Create an empty file
	void create(String file)
	{
		//Create the file
		if(TFSFileSystem.tfs_create(file.getBytes(), file.getBytes().length) > -1)
			System.out.println("File created!");
		else
			System.out.println("File not created!");
		return;
	}

	//Remove a file
	void rm(String file)
	{
		//Remove the file
		if(TFSFileSystem.tfs_delete(file.getBytes(), file.getBytes().length) > -1)
			System.out.println("File deleted!");
		else
			System.out.println("File not deleted!");
	}

	//Print number chars from file position in file
	void print(String file, int position, int number)
	{
		//Append any number of chars to the end of a file if it exists
		//Check if file exists by attempting to open it
		int fd;
		fd = TFSFileSystem.tfs_open(file.getBytes(), file.getBytes().length);
		if(fd < 0)
		{
			System.out.println("File does not exist!");
			return;
		}
		
		//File exists, so now we can initialize a buffer to hold read chars
		byte[] the_chars = new byte[number];
		
		//Now that the file is open we need to adjust the file pointer to the desired position
		TFSFileSystem.tfs_seek(fd, position);
		//Read the number of chars from position
		if(TFSFileSystem.tfs_read(fd, the_chars, the_chars.length) < 0)
		{
			System.out.println("Read 0 chars!");
			return;
		}
		
		System.out.println(number + "Chars successfully read from " + file + ":");
		
		//Print the chars
		for(int i = 0; i < number - 1; i++)
		{
			System.out.print((char)the_chars[i]);
		}
		
	}
	
	//Append any number of chars to the end of a file if it exists
	void append(String file, int number)
	{
		
		//Check if file exists by attempting to open it
		int fd;
		fd = TFSFileSystem.tfs_open(file.getBytes(), file.getBytes().length);
		if(fd < 0)
		{
			System.out.println("File does not exist!");
			return;
		}
		
		//File exists, so now we can generate the array of chars that are going to be appended
		byte[] the_chars = new byte[number];
		Arrays.fill(the_chars, (byte)'X');
		
		//Now that the file is open we need to adjust the file pointer to the end of the file
		//as this file is being opened in append mode
		TFSFileSystem.tfs_seek(fd,TFSFileSystem.tfs_get_size(fd));
		//Append the chars to the end of the file
		if(TFSFileSystem.tfs_write(fd, the_chars, the_chars.length, file.getBytes()) < 0)
		{
			System.out.println("No more room on disk to append chars!");
			return;
		}

		System.out.println(number + "Chars successfully appended to " + file);
		
	}
	
	
	//Copy file into directory if file exists and name does not already
	//exist in destination directory
	//'file' is full path to file
	//'directory' is path to directory file is to be copied into
	void cp(String file, String directory)
	{
		//Copy file
		if(TFSFileSystem.tfs_copy_file(file.getBytes(), directory.getBytes()) < 0)
		{
			System.out.println("File not copied!");
			return;
		}
		System.out.println("File successfully copied!");
	}

	//Rename file/dir to new_name if that name does not already exist within directory
	//source_file is full path, new_name is not
	void rename(String source_file, String new_name)
	{
		//Determine if source file exists by attempting to open it
		int fd;
		fd = TFSFileSystem.tfs_open(source_file.getBytes(), source_file.getBytes().length);

		//Determine if new file name already exists in directory by attempting to open it
		byte[] new_name_path = TFSFileSystem.get_parent_path(source_file.getBytes());
		if(TFSFileSystem.tfs_open(new_name_path, new_name_path.length) > 0)
		{
			System.out.println(new_name + " already exists in this directory!");
			return;
		}
		//Rename source_file to destination_file, alert user if rename was unsuccessful 
		if(TFSFileSystem.tfs_edit_name(fd, new_name.getBytes(), source_file.getBytes()) < 0)
		{
			System.out.println("File could not be renamed! (fd or name length error!");
			TFSFileSystem.tfs_close(fd);
			return;
		}
		//Let user know file was renamed
		System.out.println("File successfully renamed to " + new_name);
	}

	void exit()
	{
		TFSFileSystem.tfs_exit();
	}



/*
 * main method
 */

//class TFSMain
//{
	public static void main(String argv[]) throws InterruptedException
	{
		TFSFileSystem tfs = new TFSFileSystem();
		TFSShell shell = new TFSShell();
		System.out.println("File System started!");

		shell.start();
//		try {
			shell.join();
//		} catch (InterruptedException ie) {}
	}}
//}
