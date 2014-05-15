package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import java.util.*;
import java.io.EOFException;
import nachos.threads.ThreadedKernel;
import java.util.List;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		int numPhysPages = Machine.processor().getNumPhysPages();

		pagesLock = new Lock();	
		fileDescriptors.add( UserKernel.console.openForReading());
		fileDescriptors.add( UserKernel.console.openForWriting());
		
		nextIDMutex.acquire();
		this.processID = nextProcessIDAssignment;
		nextProcessIDAssignment++;
		nextIDMutex.release();

		joinLock = new Lock();
		joinWaiter = new Condition(joinLock);

		numProcessesMutex.acquire();
		numProcesses++;
		numProcessesMutex.release();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		boolean loaded = load(name, args);
		System.out.println("loaded check = " + loaded);
		if (loaded == false)
			return false;

		new UThread(this).setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];
	
		int bytesRead = readVirtualMemory(vaddr, bytes);
		
		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0){
				String s = new String(bytes, 0, length);
				System.out.println(s);
				return s;
			}
		}
		System.out.println(new String(bytes, 0, bytesRead));

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}


	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);
		System.out.println("INSIDE READVIRTUALMEMORY to READ ");
		
		byte[] memory = Machine.processor().getMemory();
		int pageSTART = Processor.pageFromAddress(vaddr);
		int offsetFromAddress = Processor.offsetFromAddress(vaddr);
		if(pageTable == null){
			return 0;
		}
		if(pageSTART >= pageTable.length){
			return 0;
		}
		if(pageTable[pageSTART] == null){
			return 0;
		}
		System.out.println("PAGES ARE " + pageSTART);
		//pagesLock.acquire();
		int ppn = pageTable[pageSTART].ppn;
		/* translation of virtual to physical is here */
		int paddr  = Processor.makeAddress(ppn, offsetFromAddress);
		System.out.println("PHYS ADDRESS IS AT " + paddr);
		int amount = Math.min(length, memory.length - paddr);
		System.arraycopy(memory, paddr, data, offset, amount);
		System.out.println(data + " IS WHAT I READ");
		return amount;
	}    


	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer frofm the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		int pageSTART = Processor.pageFromAddress(vaddr);
		int offsetFromAddress = Processor.offsetFromAddress(vaddr);
		if(pageTable == null){
			return 0;
		}
		if(pageSTART >= pageTable.length){
			return 0;
		}
		if(pageTable[pageSTART] == null){
			return 0;
		}
	//	pagesLock.acquire();
		int ppn = pageTable[pageSTART].ppn;
		int paddr = Processor.makeAddress(ppn, offsetFromAddress);
	
		int amount = Math.min(length, memory.length - vaddr);
		System.arraycopy(data, offset, memory, paddr, amount);
	//	pagesLock.release();
		return amount;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");
		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			System.out.println("unable to load");
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				System.out.println("fragmented executabe");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			System.out.println("args > pageSize");
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections()){
			System.out.println("unable to load sections");	
			return false;
		}
		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}
		

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		// allocate sections
		if (numPages > Machine.processor().getNumPhysPages()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		pageTable = new TranslationEntry[numPages];
		int[] allocatedPages = UserKernel.allocatePages(numPages);
		if(allocatedPages == null){
			return false;
		}
		for(int i = 0; i < pageTable.length; i++){
			pageTable[i] = new TranslationEntry(i, allocatedPages[i], true, false,false,false);
		}

		if (numPages > Machine.processor().getNumPhysPages()) {
            		coff.close();
            		// error( "\tinsufficient physical memory");
            		return false;
        	}

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				if(pageTable[vpn] == null){
					/* Not sure why this would happen, but I don't want a null pointer over here. */				
					return false;
				}
				if(section.isReadOnly()){
					pageTable[vpn].readOnly = true;				
				}
				section.loadPage(i, pageTable[vpn].ppn);			
			}
		}
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for(int i =0; i < pageTable.length; i++){
			UserKernel.free(pageTable[i].ppn);
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {

		Machine.halt();

		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return 0;
	}

	private int accessFile(int name, boolean createIfNotFound){

		try{
			String filename = readVirtualMemoryString(name, 256);
			
		/* Filename not found. */
			if(filename == null || filename.length() == 0){	
				return -1;
			}
			OpenFile file = UserKernel.fileSystem.open(filename, createIfNotFound);
			if(file != null){
				fileDescriptors.add(file);
				return fileDescriptors.size()-1;
			}
			/* File not found */
			System.out.println("RETURNING -2 LOL");
			return -1;
		}catch(Exception e){
			/* I dont know */
			return -1;
		}
	}
	
	private int handleCreate(int name){
		return accessFile(name, true);
	}
	
	private int handleOpen(int name){
		return accessFile(name, false);
	}

	private int handleRead(int fileDescriptor, int buffer, int bytesToRead){
		if(fileDescriptor < 0 || fileDescriptor >= fileDescriptors.size()){
			return -1;	
			/* invalid index*/	
		}
		OpenFile file = fileDescriptors.get(fileDescriptor);

		if(file == null){
			/* file is removed from table*/
			return -1;
		}

		byte[] writeSpace = new byte[bytesToRead];
		int readCount = file.read(writeSpace, 0, bytesToRead);
		if(readCount <= 0){
			return -1;
			/* file is empty or something weird happened*/
		}
		return writeVirtualMemory(buffer, writeSpace, 0, readCount);
		
		
	}

	private int handleWrite(int fileDescriptor, int buffer, int bytesToWrite){
		byte[] readSpace = new byte[bytesToWrite];
		int bytesRead = readVirtualMemory(buffer, readSpace);
		if(bytesRead <= 0){
			System.out.println("buffer space is emtpy");
			return -1;
			
			/*	buffer space is empty*/
		}
		if(fileDescriptor < 0 || fileDescriptor >= fileDescriptors.size()){
			System.out.println("invalid index " + fileDescriptor + " , " + fileDescriptors.size());
			return -1;

			/* invalid index*/
		}
		OpenFile file = fileDescriptors.get(fileDescriptor);
		if(file == null){
			System.out.println("file is removed from table");
			return -1;
			/* file is removed from table*/
		}
		int result = file.write(readSpace, 0, bytesToWrite);
		if(result == bytesToWrite){
			//System.out.println("RETURNING " + result);
			return result;	
		}else{
			System.out.println("write issues " + result + ", " + bytesToWrite);
			return -1;
			/* not sure what the problem would be. probably write issues*/
		}
	}

	private int handleClose(int fileDescriptor){
		if(fileDescriptor < 0 || fileDescriptor >= fileDescriptors.size()){
			System.out.println("invalid index for " + fileDescriptor);
			return -1;
			/* invalid index*/
		}
		OpenFile file = fileDescriptors.get(fileDescriptor);
		if(file == null){
System.out.println("already closed");
			return -1;
			/* already closed*/
		}
		try{
			file.close();
		}catch(Exception e){
			return -1;
			/* file is not allowed to close */
		}
		fileDescriptors.set(fileDescriptor, null);
		return 0;
	}
	
	private int handleUnlink(int name){
		try{
			String filename = readVirtualMemoryString(name, 256);
			if(filename == null || filename.length() == 0){
				return -1;
			}
			boolean status = ThreadedKernel.fileSystem.remove(filename);
			if(status){
				return 0;
			}else{
				return -1;
				/* no permission to remove*/
			}
		}catch(Exception e){
			return -1;
			/**/
		}
	}

	// Handle process exiting.
	private int handleExit(int status){
		int returnCode = 0;
		System.out.println("PROCESS " + this.processID + " EXITING");
		// Close all files used
		for(int i = 0; i < fileDescriptors.size(); i++)
		{
			if(fileDescriptors.get(i) != null)
			{
				returnCode = this.handleClose(i);
				if (returnCode != 0)
				{
					return -1; // Unable to close file
				}
			}
		}

		// Release memory (coff sections)
		this.unloadSections();

		// Set all child processes' parents to null.
		for(int i = 0; i < this.childProcesses.size(); i++)
		{
			this.childProcesses.get(i).setParentProcess(null);
		}		

		// Decrement the number of processes.
		numProcessesMutex.acquire();
		numProcesses--;
		if (numProcesses <= 0)
		{
			Kernel.kernel.terminate();
		}
		this.hasReturned = true;
		numProcessesMutex.release();
		this.joinLock.acquire();
		System.out.println("WAKING UP PARENTS...?");
		this.joinWaiter.wake();
		this.joinLock.release();
		System.out.println("EXITING");
		return status;
	}

	public void setParentProcess(UserProcess process)
	{
		this.parentProcess = process;
	}

	public void handleChildExiting(int callingProcessID)
	{
		// Wake parent if waiting for child.
		if(this.waitingForProcessID == callingProcessID)
		{
			this.joinWaiter.wake();
			this.waitingForProcessID = -1;
		}

		// Remove child from list of child processes.
		for(int i = 0; i < this.childProcesses.size(); i++)
		{
			if(this.childProcesses.get(i).getProcessID() == callingProcessID)
			{
				this.childProcesses.remove(i);
				break;
			}
		}
	}

	public int getWaitingForProcessID()
	{
		return this.waitingForProcessID;
	}

	private int handleExec(int file, int argc, int argv){
		// Get the executable name (readVirtualMemoryString)	
		System.out.println(file + " file ," + argc + " argc " + argv + " argv ");	

		if(file < 0 || argc < 0)
		{
			return -1; // Invalid parameters.
		}

		String fileName = this.readVirtualMemoryString(file, 256);
		if(fileName != null && fileName.endsWith(".coff"))
		{
			// Get all arguments
			List<String> arguments = new ArrayList<String>();
			int vaddrOffset = argv;
			for(int i = 0; i < argc; i++)
			{
				byte[] data = new byte[4];
				int numTransferredBytes = this.readVirtualMemory(vaddrOffset, data);
				if (data.length != numTransferredBytes) 
				{
					System.out.println("Not equal number of btyes transferre");
					return -1; // Not equal number of btyes transferred.
				}
				
				int argvPtr = Lib.bytesToInt(data, 0);
				String argument = null;
				if(argvPtr != 0)
				{
					argument = this.readVirtualMemoryString(argvPtr, 256);
					if (argument == null)
					{
						System.out.println("invalid argument");
					//	return -1; // invalid argument
					}else{
						arguments.add(argument);
					}
					vaddrOffset += 4;

				}
			}

			// Create a child process
			UserProcess newChildProcess = newUserProcess();
			boolean didExecute = newChildProcess.execute(fileName, arguments.toArray(new String[arguments.size()]));
			newChildProcess.setParentProcess(this);
			childProcesses.add(newChildProcess);
			if(didExecute)
			{
				return newChildProcess.getProcessID();
			}
			else
			{
				System.out.println("Did not execute.");
				return -1; // Did not execute.
			}
		}
		else
		{
			System.out.println("invalid file name");
			return -1; // Invalid file name.
		}

	}

	private int handleJoin(int processID, int status){
		// Search through all the child processes.
		int location = -1;
		for(int i = 0; i < childProcesses.size(); i++)
		{
			if (childProcesses.get(i).getProcessID() == processID)
			{
				location = i;
				break;
			}
		}
		
		if(location == -1){
			System.out.println("UANBLE TO FIND PROCESS");
			return -1;		
		}else{
			UserProcess child = childProcesses.get(location);
			child.joinLock.acquire();
			System.out.println("inside join lock");
			if(child.hasReturned == false){
				System.out.println("child has not joined yet!!!");
				child.joinWaiter.sleep();
				System.out.println("HAS THE CHILD JOINED YET?? " + child.hasReturned);
		
				child.joinLock.release();
				return 0;
			}
		}
		return -1; // The processID is not a child process.
	}

	public int getProcessID()
	{
		return this.processID;
	}

		
	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		System.out.println("SYS CALL ENTERING " + syscall + " WITH THING IS " + a0 + " - " + a1 + " - " + a2 + " - " + a3);	
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExec:
	    	     return handleExec(a0,a1,a2);
		case syscallExit:
		     return handleExit(a0);
		case syscallJoin:
	    	     return handleJoin(a0,a1);
		case syscallCreate:
	    	     return handleCreate(a0);
		case syscallOpen:
	    	     return handleOpen(a0);
		case syscallRead:
	    	     return handleRead(a0,a1,a2);
		case syscallWrite:
	    	     return handleWrite(a0,a1,a2);
		case syscallClose:
	    	     return handleClose(a0);
		case syscallUnlink:
    		     return handleUnlink(a0);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			this.handleExit(-1);
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;
	private int argc, argv;

	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';

	private List<OpenFile> fileDescriptors = new Vector<OpenFile>();
	private Lock pagesLock;	
	private List<UserProcess> childProcesses = new ArrayList<UserProcess>();
	private UserProcess parentProcess = null;
	private int processID = 0;
	private int waitingForProcessID = -1;
	private Lock joinLock;
	public Condition joinWaiter;
	public boolean hasReturned = false;

	private static int nextProcessIDAssignment = 0;
	private static Lock nextIDMutex = new Lock();
	private static int numProcesses = 0;
	private static Lock numProcessesMutex = new Lock();
}
