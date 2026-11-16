package S_Architecture;

import java.util.ArrayList;

public class Assembler {

    private ArrayList<String> lines;
    private ArrayList<String> objProgram;
    private ArrayList<String> execProgram;

    private Architecture arch;

    private ArrayList<String> commands;
    private ArrayList<String> labels;
    private ArrayList<Integer> labelsAddresses;
    private ArrayList<String> variables;

    public Assembler() {

        lines = new ArrayList<>();
        labels = new ArrayList<>();
        labelsAddresses = new ArrayList<>();
        variables = new ArrayList<>();

        objProgram = new ArrayList<>();
        execProgram = new ArrayList<>();

        arch = new Architecture();

        commands = arch.getCommandsList();
    }
}



        