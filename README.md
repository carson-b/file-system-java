# Simple File System

A java based simple file system implementation that manages files on a "hard drive". The "hard drive" consists of a file on the host system. Data structures that represent the Boot Control Block (BCB), Partition Control Block (PCB), File Allocation Table (FAT), directory structure, and File Descriptor Table (FDT) are used to manage data on the simulated hard drive. 

## Getting Started

Open a command prompt window and go to the directory where you saved the java program (TFSShell.java).

Type 'javac TFSShell.java' and press enter to compile the code.

Now, type ' java TFSShell ' to run the program.


### Prerequisites

Ensure Java is installed and the PATH variable is set.


## Commands

$ mkfs
- Make a file system – Make new PCB and FAT in the file system

$ mount -
- Mount a file system – Copy PCB and FAT in the file system into the main memory

$ sync
- Synchronize the file system – Copy PCB and FAT in the main memory back to the file system on the disk

$ prrfs
- Print all the content in PCB and FAT that are in the file system

$ prmfs
- Print all the content in PCB and FAT that are in the main memory

$ mkdir directory
- Make a directory if it does not exist

$ rmdir directory
- Remove a directory if it is empty

$ ls directory
- List file or subdirectory names in the directory, with all their related information

$ create file
- Create an empty file if it does not exist

$ rm file
- Remove a file

$ print file position number
- Print number characters from the position in the file file

$ append file number
- Append any number characters at the end of the file if it exits

$ cp source_file destination_directory
- Copy a file into a directory if destination_directory exists and source_file does not exist under destination_directory

$ rename source_file destination_file
- Rename a file if source_file exists and destination_file does not exit

$ exit
- Exit from the shell, i.e., shutdown the system


## Authors

* **Bryan Carson** 

