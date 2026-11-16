import java.util.ArrayList;
import components.*;

public class Architecture {

    private boolean simulation;
    private boolean halt;
    private int memorySize;

    // Barramentos
    private Bus extbus1;
    private Bus intbus1;
    private Bus intbus2;

    // Memória
    private Memory memory;
    private Memory statusMemory;

    // Registradores
    private Register PC;
    private Register IR;

    private Register REG0;
    private Register REG1;
    private Register REG2;
    private Register REG3;

    private Register Flags;
    private Register StkTOP;
    private Register StkBOT;

    private Ula ula;

    // Demultiplexador
    private Demux demux;

    // Listas
    private ArrayList<String> commandsList;
    private ArrayList<Register> registerList;

    // Construtor
    public Architecture() {
        componentsInstances();
    }

    private void componentsInstances() {

        // Instanciando os barramentos
        extbus1 = new Bus();
        intbus1 = new Bus();
        intbus2 = new Bus();

        // Instanciando os registradores
        PC = new Register("PC", extbus1, intbus1);
        IR = new Register("IR", extbus1, intbus1);

        REG0 = new Register("REG0", extbus1, intbus1);
        REG1 = new Register("REG1", extbus1, intbus1);
        REG2 = new Register("REG2", extbus1, intbus1);
        REG3 = new Register("REG3", extbus1, intbus1);

        StkTOP = new Register("StkTOP", extbus1, intbus1);
        StkBOT = new Register("StkBOT", extbus1, intbus1);

        // Registrador especial de flags
        Flags = new Register(2, intbus2);

        // Instanciando a ULA
        ula = new Ula(intbus1, intbus2);

        // Instanciando as memórias
        memorySize = 128;
        memory = new Memory(memorySize, extbus1);
        statusMemory = new Memory(2, extbus1);

        // Instanciando o Demux
        demux = new Demux();

        // Preenchendo as listas
        fillRegistersList();
        fillCommandsList();
    }

    protected void fillCommandsList() {

        commandsList = new ArrayList<>();

        commandsList.add("addRegReg");
        commandsList.add("addMemReg");
        commandsList.add("addRegMem");
        commandsList.add("addImmReg");

        commandsList.add("subRegReg");
        commandsList.add("subMemReg");

        commandsList.add("moveMemReg");
        commandsList.add("moveRegMem");
        commandsList.add("moveRegReg");
        commandsList.add("moveImmReg");

        commandsList.add("inc");

        commandsList.add("jmp");
        commandsList.add("jnn");
        commandsList.add("jz");
        commandsList.add("jeq");
        commandsList.add("jlw");

        commandsList.add("call");
        commandsList.add("ret");
    }

    private void fillRegistersList() {

        registerList = new ArrayList<>();

        registerList.add(REG0);
        registerList.add(REG1);
        registerList.add(REG2);
        registerList.add(REG3);

        registerList.add(PC);
        registerList.add(IR);

        registerList.add(Flags);
        registerList.add(StkBOT);
        registerList.add(StkTOP);
    }

    // Permite que outras classes acessem a lista de comandos
    public ArrayList<String> getCommandsList() {
        return commandsList;
    }

    public ArrayList<Register> getRegisterList() {
        return registerList;
    }
}