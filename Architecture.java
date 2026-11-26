package architecture;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import components.*;

/**
 * Arquitetura da figura:
 *
 *   extbus1 : memória, IR, PC, REG0..REG3   (StkTOP e StkBOT NÃO estão no extbus1)
 *   intbus1 : REG0..REG3 <-> ULA
 *   intbus2 : PC, StkTOP, StkBOT <-> ULA
 *   FLAGS   : ligado à ULA (bit 0 = Zero, bit 1 = Negativo)
 *
 * Convenções da ULA (slide 19/21): Reg1 <- Reg0 + Reg1 | Reg1 <- Reg0 - Reg1 | Reg1++
 * O resultado é SEMPRE guardado em Reg1 e precisa de um read() para sair da ULA.
 *
 * Convenções dos comandos (o ÚLTIMO operando é o destino):
 *   addRegReg  %rA %rB  : rB <- rA + rB
 *   addMemReg  mem %rB  : rB <- mem + rB
 *   addRegMem  %rA mem  : mem <- rA + mem
 *   addImmReg  imm %rB  : rB <- imm + rB
 *   subRegReg  %rA %rB  : rB <- rB - rA
 *   subMemReg  mem %rB  : rB <- mem - rB
 *   moveMemReg mem %rB  : rB <- mem
 *   moveRegMem %rA mem  : mem <- rA
 *   moveRegReg %rA %rB  : rB <- rA
 *   moveImmReg imm %rB  : rB <- imm
 *   inc %r              : r++
 *   jmp  addr           : PC <- addr
 *   jnn  addr           : se Negativo == 0, PC <- addr
 *   jz   addr           : se Zero == 1,     PC <- addr
 *   jeq  %rA %rB addr   : se rA == rB,      PC <- addr   (rA - rB, atualiza flags)
 *   jlw  %rA %rB addr   : se rA <  rB,      PC <- addr   (rA - rB, atualiza flags)
 *   call addr           : empilha o endereço da próxima instrução e PC <- addr
 *   ret                 : desempilha em PC
 *
 * Pilha: alocada logo abaixo das variáveis e em ordem DECRESCENTE.
 *   StkBOT = posição imediatamente anterior à última variável (o "fundo")
 *   StkTOP = próxima posição livre; começa igual a StkBOT (pilha vazia)
 *   call : mem[StkTOP] <- endereço de retorno; StkTOP--      (push)
 *   ret  : StkTOP++; PC <- mem[StkTOP]                        (pop)
 *   StkTOP == StkBOT significa pilha vazia.
 * As variáveis ficam no fim da memória (127, 126, ...), como no Assembler. Como
 * o arquivo .dxf não informa quantas variáveis existem, o readExec descobre o
 * menor endereço de variável usado e ajusta StkBOT/StkTOP; sem isso (ou sem
 * variáveis) a pilha começa em 127.
 * StkTOP/StkBOT só têm ligação com a intbus2. Para endereçar a memória (extbus1),
 * o valor de StkTOP passa pelo PC (que está na intbus2 e no extbus1); para chegar
 * a um REG (intbus1) ele passa pela ULA.
 *
 * Regras dos microprogramas (slides 35-37): nenhum if/while/for, nenhuma função
 * e nenhum parâmetro dentro dos métodos de comando. Por isso o "PC++" aparece
 * escrito por extenso em cada comando.
 */
public class Architecture {

    // Para o readExec descobrir os endereços de variáveis, na ordem de commandsList:
    // quantos parâmetros cada comando tem, e qual deles (1 ou 2; 0 = nenhum) é o
    // endereço de uma variável.
    private static final int[] PARAMS_COUNT = {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 3, 3, 1, 0};
    private static final int[] MEM_PARAM    = {0, 1, 2, 0, 0, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

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

    // Demultiplexador (só para operações com múltiplos registradores)
    private Demux demux;

    // Listas
    private ArrayList<String> commandsList;
    private ArrayList<Register> registerList;

    // Construtores
    public Architecture() {
        componentsInstances();
        this.simulation = false;
    }

    public Architecture(boolean sim) {
        componentsInstances();
        this.simulation = sim;
    }

    private void componentsInstances() {

        // Ordem: barramentos -> registradores -> ULA -> memórias
        extbus1 = new Bus();
        intbus1 = new Bus();
        intbus2 = new Bus();

        // Conforme a figura: PC está no extbus1 e na intbus2; IR só no extbus1;
        // REG0..3 no extbus1 e na intbus1; StkTOP/StkBOT só na intbus2.
        // (null = o registrador não tem ligação com aquele barramento)
        PC = new Register("PC", extbus1, intbus2);
        IR = new Register("IR", extbus1, null);

        REG0 = new Register("REG0", extbus1, intbus1);
        REG1 = new Register("REG1", extbus1, intbus1);
        REG2 = new Register("REG2", extbus1, intbus1);
        REG3 = new Register("REG3", extbus1, intbus1);

        StkTOP = new Register("StkTOP", null, intbus2);
        StkBOT = new Register("StkBOT", null, intbus2);

        // Registrador especial de flags (bit 0 = Zero, bit 1 = Negativo)
        Flags = new Register(2, intbus2);

        ula = new Ula(intbus1, intbus2);

        statusMemory = new Memory(2, extbus1);
        memorySize = 128;
        memory = new Memory(memorySize, extbus1);

        demux = new Demux();

        fillRegistersList();
        fillCommandsList();

        // Pilha vazia, sem variáveis: StkBOT = StkTOP = última posição da memória
        setStack(memorySize - 1);
    }

    /** Define StkBOT e StkTOP (pilha vazia) pela intbus2, o único barramento ligado a eles. */
    private void setStack(int position) {
        intbus2.put(position);
        StkBOT.internalStore();
        StkTOP.internalStore();
    }

    /**
     * A posição na lista é o código (opcode) do comando.
     * Os ids dos registradores (para o Demux) são as posições em registerList.
     */
    protected void fillCommandsList() {

        commandsList = new ArrayList<>();

        commandsList.add("addRegReg");   // 0
        commandsList.add("addMemReg");   // 1
        commandsList.add("addRegMem");   // 2
        commandsList.add("addImmReg");   // 3

        commandsList.add("subRegReg");   // 4
        commandsList.add("subMemReg");   // 5

        commandsList.add("moveMemReg");  // 6
        commandsList.add("moveRegMem");  // 7
        commandsList.add("moveRegReg");  // 8
        commandsList.add("moveImmReg");  // 9

        commandsList.add("inc");         // 10

        commandsList.add("jmp");         // 11
        commandsList.add("jnn");         // 12
        commandsList.add("jz");          // 13
        commandsList.add("jeq");         // 14
        commandsList.add("jlw");         // 15

        commandsList.add("call");        // 16
        commandsList.add("ret");         // 17
    }

    private void fillRegistersList() {

        registerList = new ArrayList<>();

        registerList.add(REG0);   // 0
        registerList.add(REG1);   // 1
        registerList.add(REG2);   // 2
        registerList.add(REG3);   // 3

        registerList.add(PC);     // 4
        registerList.add(IR);     // 5

        registerList.add(Flags);  // 6
        registerList.add(StkBOT); // 7
        registerList.add(StkTOP); // 8
    }

    // ------------------------------------------------------------------
    // Getters
    // ------------------------------------------------------------------
    public ArrayList<String> getCommandsList() {
        return commandsList;
    }

    public ArrayList<Register> getRegisterList() {
        return registerList;
    }

    public ArrayList<Register> getRegistersList() {
        return registerList;
    }

    public Bus getExtbus1() { return extbus1; }
    public Bus getIntbus1() { return intbus1; }
    public Bus getIntbus2() { return intbus2; }

    public Memory getMemory() { return memory; }
    public Memory getStatusMemory() { return statusMemory; }

    public Register getPC() { return PC; }
    public Register getIR() { return IR; }
    public Register getREG0() { return REG0; }
    public Register getREG1() { return REG1; }
    public Register getREG2() { return REG2; }
    public Register getREG3() { return REG3; }
    public Register getFlags() { return Flags; }
    public Register getStkTOP() { return StkTOP; }
    public Register getStkBOT() { return StkBOT; }

    public Ula getUla() { return ula; }
    public Demux getDemux() { return demux; }

    public int getMemorySize() { return memorySize; }
    public boolean isHalt() { return halt; }

    // ------------------------------------------------------------------
    // Auxiliares (NÃO são microprogramas: aqui pode haver if, parâmetros...)
    // ------------------------------------------------------------------

    /** Atualiza Zero (bit 0) e Negativo (bit 1) a partir de um resultado. */
    private void setStatusFlags(int result) {
        Flags.setBit(0, 0);
        Flags.setBit(1, 0);
        if (result == 0) {
            Flags.setBit(0, 1);
        }
        if (result < 0) {
            Flags.setBit(1, 1);
        }
    }

    /** Registrador -> extbus1; o registrador é o escolhido no demux. */
    private void registersRead() {
        registerList.get(demux.getValue()).read();
    }

    /** extbus1 -> registrador escolhido no demux. */
    private void registersStore() {
        registerList.get(demux.getValue()).store();
    }

    /** Registrador escolhido no demux -> barramento interno. */
    private void registersInternalRead() {
        registerList.get(demux.getValue()).internalRead();
    }

    /** Barramento interno -> registrador escolhido no demux. */
    private void registersInternalStore() {
        registerList.get(demux.getValue()).internalStore();
    }

    // ------------------------------------------------------------------
    // Comandos (microprogramas)
    //
    // Ao entrar em cada comando, PC aponta para o OPCODE.
    // Ao sair, PC aponta para a próxima instrução (exceto jumps/call/ret).
    // "PC++"  =  PC.internalRead(); ula.internalStore(1); ula.inc();
    //            ula.internalRead(1); PC.internalStore();
    // ------------------------------------------------------------------

    /** addRegReg %rA %rB : rB <- rA + rB */
    public void addRegReg() {
        // PC++ (PC -> 1o parâmetro)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA no extbus1
        demux.setValue(extbus1.get());
        registersInternalRead();             // rA -> intbus1
        ula.store(0);                        // ULA(0) = rA
        // PC++ (PC -> 2o parâmetro)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB no extbus1
        demux.setValue(extbus1.get());
        registersInternalRead();             // rB -> intbus1
        ula.store(1);                        // ULA(1) = rB
        ula.add();                           // ULA(1) = rA + rB
        ula.read(1);                         // resultado -> intbus1
        setStatusFlags(intbus1.get());
        registersInternalStore();            // rB <- resultado (demux ainda aponta rB)
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** addMemReg mem %rB : rB <- mem + rB */
    public void addMemReg() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço no extbus1
        memory.read();                       // valor de mem[endereço] no extbus1
        IR.store();                          // guarda o valor (IR é livre após o decode)
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalRead();             // rB -> intbus1
        ula.store(0);                        // ULA(0) = rB
        IR.read();                           // valor de mem -> extbus1
        registersStore();                    // rB <- mem (temporário; rB original está na ULA)
        registersInternalRead();
        ula.store(1);                        // ULA(1) = mem
        ula.add();                           // ULA(1) = rB + mem
        ula.read(1);
        setStatusFlags(intbus1.get());
        registersInternalStore();            // rB <- resultado
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** addRegMem %rA mem : mem <- rA + mem */
    public void addRegMem() {
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA
        demux.setValue(extbus1.get());
        registersInternalRead();             // rA -> intbus1
        ula.store(0);                        // ULA(0) = rA (também serve de backup de rA)
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço no extbus1
        IR.store();                          // guarda o endereço
        memory.read();                       // valor de mem[endereço]
        registersStore();                    // rA <- mem (temporário)
        registersInternalRead();
        ula.store(1);                        // ULA(1) = mem
        ula.add();                           // ULA(1) = rA + mem
        ula.read(1);
        setStatusFlags(intbus1.get());
        registersInternalStore();            // rA <- resultado (temporário, para ir à memória)
        IR.read();                           // endereço -> extbus1
        memory.store();                      // 1a etapa do store: endereço
        registersRead();                     // resultado -> extbus1
        memory.store();                      // 2a etapa do store: dado
        ula.read(0);                         // rA original -> intbus1
        registersInternalStore();            // rA restaurado
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** addImmReg imm %rB : rB <- imm + rB */
    public void addImmReg() {
        // PC++ (PC -> imediato)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // imediato no extbus1
        IR.store();
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(0);                        // ULA(0) = rB
        IR.read();                           // imediato -> extbus1
        registersStore();                    // rB <- imm (temporário)
        registersInternalRead();
        ula.store(1);                        // ULA(1) = imm
        ula.add();
        ula.read(1);
        setStatusFlags(intbus1.get());
        registersInternalStore();            // rB <- resultado
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** subRegReg %rA %rB : rB <- rB - rA */
    public void subRegReg() {
        // PC++ (PC -> 1o parâmetro)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA
        demux.setValue(extbus1.get());
        registersRead();                     // valor de rA -> extbus1
        IR.store();                          // guarda o valor de rA no IR
        // PC++ (PC -> 2o parâmetro)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(0);                        // ULA(0) = rB
        IR.read();                           // valor de rA -> extbus1
        registersStore();                    // rB <- rA (temporário; rB original está na ULA)
        registersInternalRead();
        ula.store(1);                        // ULA(1) = rA
        ula.sub();                           // ULA(1) = rB - rA
        ula.read(1);
        setStatusFlags(intbus1.get());
        registersInternalStore();            // rB <- resultado
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** subMemReg mem %rB : rB <- mem - rB */
    public void subMemReg() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço
        memory.read();                       // valor
        IR.store();
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(1);                        // ULA(1) = rB (o valor original fica guardado na ULA)
        IR.read();                           // valor de mem -> extbus1
        registersStore();                    // rB <- mem (temporário; nada se perde: rB original está na ULA)
        registersInternalRead();
        ula.store(0);                        // ULA(0) = mem
        ula.sub();                           // ULA(1) = ULA(0) - ULA(1) = mem - rB
        ula.read(1);
        setStatusFlags(intbus1.get());
        registersInternalStore();            // rB <- resultado
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** moveMemReg mem %rB : rB <- mem */
    public void moveMemReg() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço
        memory.read();                       // valor
        IR.store();
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        IR.read();                           // valor -> extbus1
        registersStore();                    // rB <- valor
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** moveRegMem %rA mem : mem <- rA */
    public void moveRegMem() {
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA
        demux.setValue(extbus1.get());
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço no extbus1
        memory.store();                      // 1a etapa: endereço
        registersRead();                     // rA -> extbus1
        memory.store();                      // 2a etapa: dado
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** moveRegReg %rA %rB : rB <- rA */
    public void moveRegReg() {
        // PC++ (PC -> 1o parâmetro)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA
        demux.setValue(extbus1.get());
        registersInternalRead();             // rA -> intbus1 (fica lá enquanto PC avança)
        // PC++ (PC -> 2o parâmetro) -- só usa intbus2 e ULA
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalStore();            // rB <- intbus1
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** moveImmReg imm %rB : rB <- imm */
    public void moveImmReg() {
        // PC++ (PC -> imediato)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // imediato
        IR.store();
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        IR.read();                           // imediato -> extbus1
        registersStore();                    // rB <- imediato
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** inc %r : r++ */
    public void inc() {
        // PC++ (PC -> registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id do registrador
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(1);                        // ULA(1) = r
        ula.inc();                           // ULA(1) = r + 1
        ula.read(1);
        setStatusFlags(intbus1.get());
        registersInternalStore();            // r <- r + 1
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
    }

    /** jmp addr : PC <- addr */
    public void jmp() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço de destino
        PC.store();                          // PC <- destino
    }

    /** jnn addr : se Negativo == 0, PC <- addr. Sem if: statusMemory escolhe o destino. */
    public void jnn() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço de destino
        statusMemory.storeIn0();             // posição 0 = destino (Neg = 0)
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();                           // endereço da próxima instrução
        statusMemory.storeIn1();             // posição 1 = próxima instrução (Neg = 1)
        extbus1.put(Flags.getBit(1));        // bit Negativo seleciona a posição
        statusMemory.read();
        PC.store();
    }

    /** jz addr : se Zero == 1, PC <- addr. */
    public void jz() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço de destino
        statusMemory.storeIn1();             // posição 1 = destino (Zero = 1)
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        statusMemory.storeIn0();             // posição 0 = próxima instrução (Zero = 0)
        extbus1.put(Flags.getBit(0));        // bit Zero seleciona a posição
        statusMemory.read();
        PC.store();
    }

    /** jeq %rA %rB addr : se rA == rB, PC <- addr. */
    public void jeq() {
        // PC++ (PC -> 1o registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(0);                        // ULA(0) = rA
        // PC++ (PC -> 2o registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(1);                        // ULA(1) = rB
        ula.sub();                           // ULA(1) = rA - rB
        ula.read(1);
        setStatusFlags(intbus1.get());       // só para atualizar as flags
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço de destino
        statusMemory.storeIn1();             // posição 1 = destino (Zero = 1)
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        statusMemory.storeIn0();             // posição 0 = próxima instrução
        extbus1.put(Flags.getBit(0));
        statusMemory.read();
        PC.store();
    }

    /** jlw %rA %rB addr : se rA < rB, PC <- addr. */
    public void jlw() {
        // PC++ (PC -> 1o registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rA
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(0);                        // ULA(0) = rA
        // PC++ (PC -> 2o registrador)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // id de rB
        demux.setValue(extbus1.get());
        registersInternalRead();
        ula.store(1);                        // ULA(1) = rB
        ula.sub();                           // ULA(1) = rA - rB
        ula.read(1);
        setStatusFlags(intbus1.get());
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço de destino
        statusMemory.storeIn1();             // posição 1 = destino (Neg = 1)
        // PC++ (PC -> próxima instrução)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        statusMemory.storeIn0();             // posição 0 = próxima instrução
        extbus1.put(Flags.getBit(1));
        statusMemory.read();
        PC.store();
    }

    /** call addr : mem[StkTOP] <- endereço da próxima instrução; StkTOP--; PC <- addr */
    public void call() {
        // PC++ (PC -> endereço)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.read();
        memory.read();                       // endereço da sub-rotina
        IR.store();                          // guarda o destino
        // PC++ (PC -> próxima instrução = endereço de retorno)
        PC.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        PC.internalStore();
        PC.internalRead();
        ula.internalStore(1);                // ULA(1) = endereço de retorno (guardado na ULA)
        // StkTOP não está no extbus1: passa pelo PC (que está na intbus2 e no extbus1)
        StkTOP.internalRead();               // StkTOP -> intbus2
        PC.internalStore();                  // PC <- StkTOP
        PC.read();                           // endereço do topo -> extbus1
        memory.store();                      // 1a etapa do store: endereço
        ula.internalRead(1);                 // endereço de retorno -> intbus2
        PC.internalStore();                  // PC <- endereço de retorno
        PC.read();                           // -> extbus1
        memory.store();                      // 2a etapa do store: dado (push)
        // StkTOP-- : a ULA só faz Reg1 <- Reg0 - Reg1, e Reg0 só é carregado pelo
        // intbus1 (REGs). Truque: TOP - TOP = 0; 0 + 1 = 1; TOP - 1.
        // O PC agora está livre (o retorno já está na memória e o PC receberá o
        // destino no fim), então ele guarda REG0 temporariamente.
        REG0.read();
        PC.store();                          // PC <- REG0 (salva)
        // REG0 <- StkTOP: StkTOP só fala com a intbus2, então vai pela ULA até a intbus1
        StkTOP.internalRead();               // StkTOP -> intbus2
        ula.internalStore(1);                // ULA(1) = TOP
        ula.read(1);                         // ULA(1) -> intbus1
        REG0.internalStore();                // REG0 = TOP
        REG0.internalRead();
        ula.store(0);                        // ULA(0) = TOP
        StkTOP.internalRead();
        ula.internalStore(1);                // ULA(1) = TOP
        ula.sub();                           // ULA(1) = 0
        ula.inc();                           // ULA(1) = 1
        ula.sub();                           // ULA(1) = TOP - 1
        ula.internalRead(1);
        StkTOP.internalStore();              // StkTOP = TOP - 1
        PC.read();
        REG0.store();                        // restaura REG0
        IR.read();                           // destino -> extbus1
        PC.store();                          // PC <- destino
    }

    /** ret : StkTOP++; PC <- mem[StkTOP] */
    public void ret() {
        // StkTOP++
        StkTOP.internalRead();
        ula.internalStore(1);
        ula.inc();
        ula.internalRead(1);
        StkTOP.internalStore();
        // lê mem[StkTOP] passando pelo PC (StkTOP não está no extbus1)
        StkTOP.internalRead();               // StkTOP -> intbus2
        PC.internalStore();                  // PC <- StkTOP
        PC.read();                           // endereço do dado empilhado -> extbus1
        memory.read();                       // endereço de retorno
        PC.store();                          // PC <- retorno
    }

    // ------------------------------------------------------------------
    // Ciclo de execução
    // ------------------------------------------------------------------

    /** Busca: IR <- mem[PC]. PC continua apontando para o opcode. */
    private void fetch() {
        PC.read();
        memory.read();
        IR.store();
        simulationFetch();
    }

    /** Decodifica o opcode que está no IR e chama o microprograma. */
    private void decodeExecute() {
        IR.read();
        int command = extbus1.get();
        simulationDecodeExecuteBefore(command);
        switch (command) {
            case 0:  addRegReg();  break;
            case 1:  addMemReg();  break;
            case 2:  addRegMem();  break;
            case 3:  addImmReg();  break;
            case 4:  subRegReg();  break;
            case 5:  subMemReg();  break;
            case 6:  moveMemReg(); break;
            case 7:  moveRegMem(); break;
            case 8:  moveRegReg(); break;
            case 9:  moveImmReg(); break;
            case 10: inc();        break;
            case 11: jmp();        break;
            case 12: jnn();        break;
            case 13: jz();         break;
            case 14: jeq();        break;
            case 15: jlw();        break;
            case 16: call();       break;
            case 17: ret();        break;
            default: halt = true;  break;   // -1 (ou opcode inválido) encerra
        }
        if (simulation) {
            simulationDecodeExecuteAfter();
        }
    }

    /** Executa o programa que está na memória, a partir da posição 0. */
    public void controlUnitEexec() {
        halt = false;
        extbus1.put(0);
        PC.store();                          // PC <- 0
        while (!halt) {
            fetch();
            decodeExecute();
        }
    }

    /**
     * Lê um arquivo executável (.dxf, um inteiro por linha) para a memória e
     * posiciona a pilha logo abaixo das variáveis usadas pelo programa.
     */
    public void readExec(String filename) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(filename + ".dxf"));
        ArrayList<Integer> program = new ArrayList<>();
        String linha;
        int i = 0;
        while ((linha = br.readLine()) != null) {
            linha = linha.trim();
            if (linha.isEmpty()) {
                continue;
            }
            int word = Integer.parseInt(linha);
            program.add(word);
            extbus1.put(i);
            memory.store();                  // endereço
            extbus1.put(word);
            memory.store();                  // dado
            i++;
        }
        br.close();
        setStack(firstFreePosition(program));
    }

    /**
     * Percorre o programa instrução por instrução (cada opcode tem um número fixo de
     * parâmetros) e acha o menor endereço de variável usado. A pilha começa na
     * posição imediatamente anterior a ele. Sem variáveis, começa na última
     * posição da memória. Para no -1 (fim do programa) ou em qualquer opcode
     * desconhecido.
     */
    private int firstFreePosition(ArrayList<Integer> program) {
        int lowest = memorySize;             // nenhuma variável: primeira livre = memorySize - 1
        int i = 0;
        while (i < program.size()) {
            int opcode = program.get(i);
            if (opcode < 0 || opcode >= PARAMS_COUNT.length) {
                break;
            }
            int param = MEM_PARAM[opcode];
            if ((param > 0) && (i + param < program.size())) {
                int address = program.get(i + param);
                if ((address >= 0) && (address < lowest)) {
                    lowest = address;
                }
            }
            i += 1 + PARAMS_COUNT[opcode];
        }
        return Math.max(lowest - 1, 0);
    }

    public void read(String filename) throws IOException {
        readExec(filename);
    }

    // ------------------------------------------------------------------
    // Modo simulação
    // ------------------------------------------------------------------
    private void printRegisters() {
        for (Register r : registerList) {
            System.out.print(r.getRegisterName() + ": " + r.getData() + "  ");
        }
        System.out.println();
    }

    private void simulationFetch() {
        if (simulation) {
            System.out.println("----------FETCH---------");
            printRegisters();
        }
    }

    private void simulationDecodeExecuteBefore(int command) {
        if (simulation) {
            System.out.println("----------BEFORE Decode and Execute---------");
            printRegisters();
            String nome = (command >= 0 && command < commandsList.size())
                    ? commandsList.get(command) : "halt";
            System.out.println("Instruction: " + nome + " (" + command + ")");
        }
    }

    private void simulationDecodeExecuteAfter() {
        System.out.println("----------AFTER Decode and Execute---------");
        printRegisters();
    }

    /** Mostra as posições de memória das variáveis (do fim da memória até logo acima do fundo da pilha). */
    public void printVariables() {
        StkBOT.internalRead();
        int bottom = intbus2.get();
        System.out.println("Variaveis (endereco = valor):");
        for (int address = memorySize - 1; address > bottom; address--) {
            extbus1.put(address);
            memory.read();
            System.out.println("  " + address + " = " + extbus1.get());
        }
    }

    /** Uso: java architecture.Architecture <nome do .dxf, sem extensão>  (padrão: program) */
    public static void main(String[] args) throws IOException {
        String name = (args.length > 0) ? args[0] : "program";
        Architecture arch = new Architecture(true);
        arch.readExec(name);
        arch.controlUnitEexec();
        arch.printVariables();
    }
}