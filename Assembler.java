package assembler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import components.Register;

import architecture.Architecture;

public class Assembler {

	private ArrayList<String> lines;
	private ArrayList<String> objProgram;
	private ArrayList<String> execProgram;
	private Architecture arch;
	private ArrayList<String>commands;
	private ArrayList<String>labels;
	private ArrayList<Integer> labelsAdresses;
	private ArrayList<String>variables;


	public Assembler() {
		lines = new ArrayList<>();
		labels = new ArrayList<>();
		labelsAdresses = new ArrayList<>();
		variables = new ArrayList<>();
		objProgram = new ArrayList<>();
		execProgram = new ArrayList<>();
		arch = new Architecture();
		commands = arch.getCommandsList();
	}

	//getters

	public ArrayList<String> getObjProgram() {
		return objProgram;
	}

	/**
	 * These methods getters and set below are used only for TDD purposes
	 * @param lines
	 */

	protected ArrayList<String> getLabels() {
		return labels;
	}

	protected ArrayList<Integer> getLabelsAddresses() {
		return labelsAdresses;
	}

	protected ArrayList<String> getVariables() {
		return variables;
	}

	protected ArrayList<String> getExecProgram() {
		return execProgram;
	}

	protected void setLines(ArrayList<String> lines) {
		this.lines = lines;
	}

	protected void setExecProgram(ArrayList<String> lines) {
		this.execProgram = lines;
	}


	/*
	 * An assembly program is always in the following template
	 * <variables>
	 * <commands>
	 * Obs.
	 * 		variables names are always started with alphabetical char
	 * 	 	variables names must contains only alphabetical and numerical chars
	 *      variables names never uses any command name
	 * 		names ended with ":" identifies labels i.e. address in the memory
	 * 		Commands are only that ones known in the architecture. No comments allowed
	 *
	 * 		The assembly file must have the extention .dsf
	 * 		The executable file must have the extention .dxf
	 *
	 * Assembly syntax of this architecture (the LAST operand is the destination):
	 * 		add  %rA %rB     rB <- rA + rB        (addRegReg)
	 * 		add  var %rB     rB <- var + rB       (addMemReg)
	 * 		add  %rA var     var <- rA + var      (addRegMem)
	 * 		add  imm %rB     rB <- imm + rB       (addImmReg)
	 * 		sub  %rA %rB     rB <- rB - rA        (subRegReg)
	 * 		sub  var %rB     rB <- rB - var       (subMemReg)
	 * 		move var %rB     rB <- var            (moveMemReg)
	 * 		move %rA var     var <- rA            (moveRegMem)
	 * 		move %rA %rB     rB <- rA             (moveRegReg)
	 * 		move imm %rB     rB <- imm            (moveImmReg)
	 * 		inc  %r          r++
	 * 		jmp  label       pc <- label
	 * 		jnn  label       if not negative, pc <- label
	 * 		jz   label       if zero, pc <- label
	 * 		jeq  %rA %rB label   if rA == rB, pc <- label
	 * 		jlw  %rA %rB label   if rA <  rB, pc <- label
	 * 		call label       push return address; pc <- label
	 * 		ret              pop return address into pc
	 *
	 * Registers are prefixed with %, immediates are integers, anything else is a
	 * variable (or a label, in the jumps and in the call).
	 */



	/**
	 * This method reads an entire file in assembly
	 * @param filename
	 * @throws IOException
	 */
	public void read(String filename) throws IOException {
		   BufferedReader br = new BufferedReader(new
		   FileReader(filename+".dsf"));
		   String linha;
		   while ((linha = br.readLine()) != null) {
			     lines.add(linha);
			}
			br.close();

	}


	/**
	 * This method scans the strings in lines
	 * generating, for each one, the corresponding machine code
	 * @param lines
	 */
	public void parse() {
		for (String s:lines) {
			s = s.trim();
			if (s.isEmpty()) { //blank lines are ignored
				continue;
			}
			String tokens[] = s.split("\\s+");
			if (findCommandNumber(tokens)>=0) { //the line is a command
				proccessCommand(tokens);
			}
			else { //the line is not a command: so, it can be a variable or a label
				if (tokens[0].endsWith(":")){ //if it ends with : it is a label
					String label = tokens[0].substring(0, tokens[0].length()-1); //removing the last character
					labels.add(label);
					labelsAdresses.add(objProgram.size());
				}
				else if ((tokens.length > 1) || isMnemonic(tokens[0])) {
					//a known command with wrong parameters (or a line with several words that is not a command)
					System.out.println("FATAL ERROR! Invalid command or parameters: "+s);
				}
				else //otherwise, it must be a variable
					variables.add(tokens[0]);
			}
		}

	}



	/**
	 * This method processes a command, putting it and its parameters (if they have)
	 * into the final array.
	 * Registers (%reg) and immediates (numbers) are copied as they are.
	 * Any other parameter is a variable or a label, and receives the flag "&"
	 * (the position in memory that will be replaced later by makeExecutable)
	 * @param tokens
	 */
	protected void proccessCommand(String[] tokens) {
		int commandNumber = findCommandNumber(tokens);
		if (commandNumber < 0) { //not a valid command
			return;
		}
		int count = parametersCount(commands.get(commandNumber));
		objProgram.add(Integer.toString(commandNumber));
		for (int i = 1; i <= count; i++) {
			objProgram.add(convertParameter(tokens[i]));
		}
	}

	/**
	 * Registers and immediates stay as they are.
	 * Variables and labels receive the "&" flag
	 */
	private String convertParameter(String parameter) {
		if (isRegister(parameter) || isNumber(parameter)) {
			return parameter;
		}
		return "&"+parameter;
	}

	/**
	 * Number of parameters of each command
	 */
	private int parametersCount(String commandName) {
		switch (commandName) {
			case "ret":
				return 0;
			case "inc":
			case "jmp":
			case "jnn":
			case "jz":
			case "call":
				return 1;
			case "jeq":
			case "jlw":
				return 3;
			default: //add*, sub*, move*
				return 2;
		}
	}

	private boolean isRegister(String parameter) {
		return parameter != null && parameter.startsWith("%");
	}

	private boolean isNumber(String parameter) {
		return parameter != null && parameter.matches("-?\\d+");
	}

	private boolean isMnemonic(String word) {
		return commands.contains(word) || "add".equals(word) || "sub".equals(word) || "move".equals(word);
	}

	/**
	 * Returns Reg, Imm or Mem, according to the kind of the parameter.
	 * These words are used to build the name of the command (i.e. moveRegMem)
	 */
	private String parameterKind(String parameter) {
		if (isRegister(parameter))
			return "Reg";
		if (isNumber(parameter))
			return "Imm";
		return "Mem";
	}

	/**
	 * This method uses the tokens to search a command
	 * in the commands list and returns its id.
	 * Some commands (as move) can have multiple formats (reg reg, mem reg, reg mem) and
	 * multiple ids, one for each format.
	 * Returns -1 if the command does not exist, or if it has not enough parameters.
	 * @param tokens
	 * @return
	 */
	private int findCommandNumber(String[] tokens) {
		int p = commands.indexOf(tokens[0]);
		if (p<0){ //the command isn't in the list. So it must have multiple formats
			if ("add".equals(tokens[0]) || "sub".equals(tokens[0]) || "move".equals(tokens[0]))
				p = proccessMultipleFormats(tokens);
		}
		else { //the command exists, but it needs all of its parameters
			if (tokens.length-1 < parametersCount(tokens[0]))
				p = -1;
		}
		return p;
	}

	/**
	 * This method proccess the commands with multiple formats (add, sub and move).
	 * The format is given by the kind of the two parameters:
	 * register (%reg), immediate (number) or memory (variable).
	 * So, "move %REG0 x" means moveRegMem, "move 4 %REG1" means moveImmReg, and so on.
	 * @param tokens
	 * @return the command id, or -1 if this format does not exist for this command
	 */
	private int proccessMultipleFormats(String[] tokens) {
		if (tokens.length < 3)
			return -1;
		String name = tokens[0] + parameterKind(tokens[1]) + parameterKind(tokens[2]);
		return commands.indexOf(name); //-1 if there is no such format (ex. addImmMem)
	}

	/**
	 * This method creates the executable program from the object program
	 * Step 1: check if all variables and labels mentioned in the object
	 * program are declared in the source program
	 * Step 2: allocate memory addresses (space), from the end to the begin (stack)
	 * to store variables
	 * Step 3: identify memory positions to the labels
	 * Step 4: make the executable by replacing the labels and the variables by the
	 * corresponding memory addresses
	 * @param filename
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	public void makeExecutable(String filename) throws IOException {
		if (!checkLabels())
			return;
		execProgram = (ArrayList<String>) objProgram.clone();
		replaceAllVariables();
		replaceLabels(); //replacing all labels by the address they refer to
		replaceRegisters(); //replacing all registers by the register id they refer to
		saveExecFile(filename);
		System.out.println("Finished");
	}

	/**
	 * This method replaces all the registers names by its correspondings ids.
	 * registers names must be prefixed by %
	 */
	protected void replaceRegisters() {
		int p=0;
		for (String line:execProgram) {
			if (line.startsWith("%")){ //this line is a register
				line = line.substring(1, line.length());
				int regId = searchRegisterId(line, arch.getRegistersList());
				if (regId < 0) {
					System.out.println("FATAL ERROR! Register "+line+" does not exist!");
				}
				String newLine = Integer.toString(regId);
				execProgram.set(p, newLine);
			}
			p++;
		}

	}

	/**
	 * This method replaces all variables by their addresses.
	 * The addresses o0f the variables startes in the end of the memory
	 * and decreases (creating a stack)
	 */
	protected void replaceAllVariables() {
		int position = arch.getMemorySize()-1; //starting from the end of the memory
		for (String var : this.variables) { //scanning all variables
			replaceVariable(var, position);
			position --;
		}
	}

	/**
	 * This method saves the execFile collection into the output file
	 * @param filename
	 * @throws IOException
	 */
	private void saveExecFile(String filename) throws IOException {
		File file = new File(filename+".dxf");
		BufferedWriter writer = new BufferedWriter(new FileWriter(file));
		for (String l : execProgram)
			writer.write(l+"\n");
		writer.write("-1"); //-1 is a flag indicating that the program is finished
		writer.close();

	}

	/**
	 * This method replaces all labels in the execprogram by the corresponding
	 * address they refer to
	 */
	protected void replaceLabels() {
		int i=0;
		for (String label : labels) { //searching all labels
			label = "&"+label;
			int labelPointTo = labelsAdresses.get(i);
			int lineNumber = 0;
			for (String l : execProgram) {
				if (l.equals(label)) {//this label must be replaced by the address
					String newLine = Integer.toString(labelPointTo); // the address
					execProgram.set(lineNumber, newLine);
				}
				lineNumber++;
			}
			i++;
		}

	}

	/**
	 * This method replaces all occurences of a variable
	 * name found in the object program by his address
	 * in the executable program
	 * @param var
	 * @param position
	 */
	protected void replaceVariable(String var, int position) {
		var = "&"+var;
		int i=0;
		for (String s:execProgram) {
			if (s.equals(var)) {
				s = Integer.toString(position);
				execProgram.set(i, s);
			}
			i++;
		}
	}

	/**
	 * This method checks if all labels and variables in the object program were in the source
	 * program.
	 * The labels and the variables collection are used for this
	 */
	protected boolean checkLabels() {
		System.out.println("Checking labels and variables");
		for (String line:objProgram) {
			boolean found = false;
			if (line.startsWith("&")) { //if starts with "&", it is a label or a variable
				line = line.substring(1, line.length());
				if (labels.contains(line))
					found = true;
				if (variables.contains(line))
					found = true;
				if (!found) {
					System.out.println("FATAL ERROR! Variable or label "+line+" not declared!");
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * This method searches for a register in the architecture register list
	 * by the register name
	 * @param line
	 * @param registersList
	 * @return
	 */
	private int searchRegisterId(String line, ArrayList<Register> registersList) {
		int i=0;
		for (Register r:registersList) {
			if (line.equals(r.getRegisterName())) {
				return i;
			}
			i++;
		}
		return -1;
	}

	public static void main(String[] args) throws IOException {
		String filename = args[0];
		Assembler assembler = new Assembler();
		System.out.println("Reading source assembler file: "+filename+".dsf");
		assembler.read(filename);
		System.out.println("Generating the object program");
		assembler.parse();
		System.out.println("Generating executable: "+filename+".dxf");
		assembler.makeExecutable(filename);
	}

}