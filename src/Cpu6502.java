import java.lang.String;
import java.lang.annotation.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class Cpu6502 {
	@Retention(RetentionPolicy.RUNTIME)
	@Target(value=ElementType.TYPE)
	public @interface InstructionClass {}
	
	private enum AddressingMode { IMM, ZP, ZPX, ZPY, IZX, IZY, ABS, ABSX, ABSY, IND, REL, ACC, NONE };
	
	private static final int STACK_MEMORY = 0x100;
		
	private class Registers {
		int PC;
		int SP;
		int A;
		int X;
		int Y;
		boolean carry;
		boolean zero;
		boolean interrupt;
		boolean decimal;
		boolean brk;
		boolean overflow;
		boolean negative;
			
		/**
		 * @return all status flags in a single byte 
		 */
		public int getFlags() {
			return (carry?1:0)|((zero?1:0)<<1)|((interrupt?1:0)<<2)|((decimal?1:0)<<3)|((brk?1:0)<<4)|((overflow?1:0)<<6)|((negative?1:0)<<7);			  
		}
	};
	private Registers regs = new Registers();		
	private Instruction[] instList = initInstructionList();
	private static AddressingMode[] opcodeAddressingMode = initOpcodeAddressingMode();
	private int[] memory = new int[65536];
	private int ticks;

	abstract class Instruction {
		protected String name;
		protected int opcode;
		protected int length;
		protected AddressingMode mode;
		protected int numCycles;
		protected boolean extraCycle;

		public Instruction(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super();
			this.name = name;
			this.opcode = opcode;
			this.length = length;
			this.mode = mode;
			this.numCycles = numCycles;
			this.extraCycle = extraCycle;
		}		
		
		/**
		 * @param operand the instruction operand
		 * @return number of cycles elapsed
		 */
		abstract public int execute(int operand);

		public int getOpcode() {
			return opcode;
		}
		
		protected void updateNZ(int res) {
			regs.zero = (res == 0);
			regs.negative = (res&0x80) != 0;
		}
		
		protected int convertOperand(int operand) {
			if(mode == AddressingMode.ACC)
				return regs.A;
			boolean isPtr =(mode != AddressingMode.IMM) && (mode != AddressingMode.REL);				
			return isPtr ? memory[operand] : operand;
		}
	}	

	class InstrADC extends Instruction {
		public InstrADC(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}

		private void bcdAdd(int operand) {
			int carry = regs.carry?1:0;
			int a = Utils6502.unpackBcd(operand);
			int b = Utils6502.unpackBcd(regs.A);			
			int res = a+b+carry;
			regs.A = res%100;
			regs.carry = res>99	;
			regs.zero = (regs.A == 0);	
			regs.overflow = (res>99);
			regs.negative = (regs.A&0x80) != 0;
			regs.A = Utils6502.packBcd(res);		 
		}
		private void binaryAdd(int operand) {
			int carry = regs.carry?1:0;
			int res = regs.A+operand+carry;
			regs.A = res&255;
			regs.carry = res>255;
			regs.zero = (regs.A == 0);	
			regs.overflow = (res>127 || res<-128);
			regs.negative = (regs.A&0x80) != 0;
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			if(regs.decimal)
				bcdAdd(operand);				
			else
				binaryAdd(operand);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrADCImm extends InstrADC
	{
		public InstrADCImm() {
			super("ADC", 0x69, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrADCZp extends InstrADC
	{
		public InstrADCZp() {
			super("ADC", 0x65, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrADCZpX extends InstrADC
	{
		public InstrADCZpX() {			
			super("ADC", 0x75, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrADCAbs extends InstrADC
	{
		public InstrADCAbs() {			
			super("ADC", 0x6D, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrADCAbsX extends InstrADC
	{
		public InstrADCAbsX() {			
			super("ADC", 0x7D, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrADCAbsY extends InstrADC
	{
		public InstrADCAbsY() {			
			super("ADC", 0x79, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrADCIndX extends InstrADC
	{
		public InstrADCIndX() {			
			super("ADC", 0x61, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrADCIndY extends InstrADC
	{
		public InstrADCIndY() {			
			super("ADC", 0x71, 2, AddressingMode.IZY, 5, true);
		}
	}

	class InstrAND extends Instruction {
		public InstrAND(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);			
			regs.A = regs.A&operand;
			updateNZ(regs.A);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrANDImm extends InstrAND
	{
		public InstrANDImm() {
			super("AND", 0x29, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrANDZp extends InstrAND
	{
		public InstrANDZp() {
			super("AND", 0x25, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrANDZpX extends InstrAND
	{
		public InstrANDZpX() {			
			super("AND", 0x35, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrANDAbs extends InstrAND
	{
		public InstrANDAbs() {			
			super("AND", 0x2D, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrANDAbsX extends InstrAND
	{
		public InstrANDAbsX() {			
			super("AND", 0x3D, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrANDAbsY extends InstrAND
	{
		public InstrANDAbsY() {			
			super("AND", 0x39, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrANDIndX extends InstrAND
	{
		public InstrANDIndX() {			
			super("AND", 0x21, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrANDIndY extends InstrAND
	{
		public InstrANDIndY() {			
			super("AND", 0x31, 2, AddressingMode.IZY, 5, true);
		}
	}
	
	class InstrASL extends Instruction {
		public InstrASL(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);			
			int res = operand<<1;
			regs.A = res&255;
			regs.carry = (res&0x100)!=0;
			updateNZ(regs.A);
			return numCycles;
		}
	}

	@InstructionClass class InstrASLAcc extends InstrASL
	{
		public InstrASLAcc() {
			super("ASL", 0x0A, 1, AddressingMode.ACC, 2, false);
		}	
	}

	@InstructionClass class InstrASLZp extends InstrASL
	{
		public InstrASLZp() {
			super("ASL", 0x06, 2, AddressingMode.ZP, 5, false);
		}
	}

	@InstructionClass class InstrASLZpX extends InstrASL
	{
		public InstrASLZpX() {			
			super("ASL", 0x16, 2, AddressingMode.ZPX, 6, false);
		}
	}

	@InstructionClass class InstrASLAbs extends InstrASL
	{
		public InstrASLAbs() {			
			super("ASL", 0x0E, 3, AddressingMode.ABS, 6, false);
		}
	}

	@InstructionClass class InstrASLAbsX extends InstrASL
	{
		public InstrASLAbsX() {			
			super("ASL", 0x1E, 3, AddressingMode.ABSX, 7, false);
		}
	}

	abstract class InstrBXX extends Instruction {
		public InstrBXX(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		abstract protected boolean mustBranch();
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			int extraCycle = 0;
			if(mustBranch()) {
				regs.PC += operand; //If operand==0, infinite loop?
				extraCycle++;
			}
			return numCycles+extraCycle;
		}
	}

	@InstructionClass class InstrBCC extends InstrBXX {
		public InstrBCC() {
			super("BCC", 0x90, 2, AddressingMode.REL, 2, true);			
		}
		
		protected boolean mustBranch() { return !regs.carry; }
	}
	
	@InstructionClass class InstrBCS extends InstrBXX {
		public InstrBCS() {
			super("BCS", 0xB0, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return regs.carry; }
	}

	@InstructionClass class InstrBEQ extends InstrBXX {
		public InstrBEQ() {
			super("BEQ", 0xF0, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return regs.zero; }
	}
	
	@InstructionClass class InstrBNE extends InstrBXX {
		public InstrBNE() {
			super("BNE", 0xD0, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return !regs.zero; }
	}

	@InstructionClass class InstrBPL extends InstrBXX {
		public InstrBPL() {
			super("BPL", 0x10, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return !regs.negative; }
	}

	@InstructionClass class InstrBMI extends InstrBXX {
		public InstrBMI() {
			super("BMI", 0x30, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return regs.negative; }
	}

	@InstructionClass class InstrBVC extends InstrBXX {
		public InstrBVC() {
			super("BVC", 0x50, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return !regs.overflow; }
	}

	@InstructionClass class InstrBVS extends InstrBXX {
		public InstrBVS() {
			super("BVS", 0x70, 2, AddressingMode.REL, 2, true);			
		}
		protected boolean mustBranch() { return regs.overflow; }
	}

	class InstrBIT extends Instruction {
		public InstrBIT(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			regs.negative = (operand&0x80) != 0;
			regs.overflow = (operand&0x40) != 0;
			int res = regs.A&operand;
			regs.zero = (res == 0);			
			return numCycles;
		}
	}

	@InstructionClass class InstrBITZp extends InstrBIT
	{
		public InstrBITZp() {
			super("BIT", 0x24, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrBITAbs extends InstrBIT
	{
		public InstrBITAbs() {			
			super("BIT", 0x2C, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrBRK extends Instruction {
		public InstrBRK() {			
			super("BRK", 0x00, 3, AddressingMode.NONE, 7, false);
		}
		
		public int execute(int operand) {
			regs.brk = true;
			pushInt(regs.PC);
			pushByte(regs.getFlags());
			regs.PC = readInt(0xFFFE);
			return numCycles;
		}
	}

	@InstructionClass class InstrCLC extends Instruction {
		public InstrCLC() {			
			super("CLC", 0x18, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.carry = false;
			return numCycles;
		}
	}

	@InstructionClass class InstrSEC extends Instruction {
		public InstrSEC() {			
			super("SEC", 0x38, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.carry = true;
			return numCycles;
		}
	}

	@InstructionClass class InstrCLI extends Instruction {
		public InstrCLI() {			
			super("CLI", 0x58, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.interrupt = false;
			return numCycles;
		}
	}

	@InstructionClass class InstrSEI extends Instruction {
		public InstrSEI() {			
			super("SEI", 0x78, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.interrupt = true;
			return numCycles;
		}
	}

	@InstructionClass class InstrCLV extends Instruction {
		public InstrCLV() {			
			super("CLV", 0xB8, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.overflow = false;
			return numCycles;
		}
	}

	@InstructionClass class InstrCLD extends Instruction {
		public InstrCLD() {			
			super("CLD", 0xD8, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.decimal = false;
			return numCycles;
		}
	}

	@InstructionClass class InstrSED extends Instruction {
		public InstrSED() {			
			super("SED", 0xF8, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			regs.decimal = true;
			return numCycles;
		}
	}

	class InstrCMP extends Instruction {
		public InstrCMP(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			int res = regs.A-operand;
			regs.carry = (regs.A >= operand);
			regs.zero = (regs.A == operand);
			regs.negative = (res&0x80) == 0;
			return numCycles;
		}
	}
	
	@InstructionClass class InstrCMPImm extends InstrCMP
	{
		public InstrCMPImm() {
			super("CMP", 0xC9, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrCMPZp extends InstrCMP
	{
		public InstrCMPZp() {
			super("CMP", 0xC5, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrCMPZpX extends InstrCMP
	{
		public InstrCMPZpX() {			
			super("CMP", 0xD5, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrCMPAbs extends InstrCMP
	{
		public InstrCMPAbs() {			
			super("CMP", 0xCD, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrCMPAbsX extends InstrCMP
	{
		public InstrCMPAbsX() {			
			super("CMP", 0xDD, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrCMPAbsY extends InstrCMP
	{
		public InstrCMPAbsY() {			
			super("CMP", 0xD9, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrCMPIndX extends InstrCMP
	{
		public InstrCMPIndX() {			
			super("CMP", 0xC1, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrCMPIndY extends InstrCMP
	{
		public InstrCMPIndY() {			
			super("CMP", 0xD1, 2, AddressingMode.IZY, 5, true);
		}
	}

	private final Instruction[] initInstructionList() {
		Instruction[] instList = new Instruction[255];		
		try {			
			for(Class<?> c : Cpu6502.class.getDeclaredClasses()) {
				if(c.isAnnotationPresent(InstructionClass.class)) {					
					System.out.println(c);
					Instruction inst;					
					Constructor<?> ctor = c.getConstructor(Cpu6502.class);					
					inst = (Instruction)(ctor.newInstance(this));
					instList[inst.getOpcode()] = inst;
				}
			}
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		return instList;
	}
	
	public Cpu6502(int startAddress) {
		reset(startAddress);
	}
	
	public void reset(int startAddress) {
		regs.PC = startAddress;
		regs.SP = 0xFF;
		regs.A = 0;
		regs.X = 0;
		regs.Y = 0;
		regs.carry = false;
		regs.zero = false;
		regs.interrupt = false;
		regs.decimal = false;
		regs.brk = false;
		regs.overflow = false;
		regs.negative = false;
	}
	
	public int readInt(int addr)
	{
		return (memory[addr]<<8)|memory[addr+1];			
	}
	
	private int zeroPageAddressing(int addr) {
		return addr;
	}

	private int zeroPageAddressingX(int addr) {		
		return (addr+regs.X)&255;
	}

	private int zeroPageAddressingY(int addr) {		
		return (addr+regs.Y)&255;
	}

	private int absAddressing(int addr) {		
		return addr;
	}

	private int absAddressingX(int addr) {		
		return addr+regs.X;
	}

	private int absAddressingY(int addr) {		
		return addr+regs.Y;
	}

	private int indAddressingX(int addr) {		
		int zp = (addr+regs.X)&255;
		return readInt(zp);
	}

	private int indAddressingY(int addr) {		
		int absAddr = readInt(addr)+regs.Y;	
		return absAddr;
	}
	
	private void pushInt(int val) {
		int high = (val>>8)&0xf;
		int low = val&0xf;
		pushByte(high);
		pushByte(low);
	}

	private int popInt() {
		int low = popByte();
		int high = popByte();
		return (high<<8)|low;
	}

	private void pushByte(int val) {
		int addr = regs.SP+STACK_MEMORY;
		regs.SP = (regs.SP-1)&0xFF;
		memory[addr] = val;
	}

	private int popByte() {		
		regs.SP = (regs.SP+1)&0xFF;
		int addr = regs.SP+STACK_MEMORY;
		return memory[addr];		
	}
	
	static private AddressingMode computeAddressingMode(int opcode) {
		int lowNibble = opcode&0xf; 
		int highNibble = (opcode>>4)&0xf;
	
		if(lowNibble==0x0) {
			if(highNibble%2==1)
				return AddressingMode.REL;
			if(highNibble>=0x8)
				return AddressingMode.IMM;
			if(highNibble==0x2)
				return AddressingMode.ABS;
			return AddressingMode.NONE;
		}
		if(lowNibble==0x2) {
			return AddressingMode.IMM;
		}
		if(lowNibble==1||lowNibble==3) {
			return (highNibble%2==0)?AddressingMode.IZX:AddressingMode.IZY;
		}
		if(lowNibble>=4&&lowNibble<=7) {		
			return (highNibble%2==0)?AddressingMode.ZP:AddressingMode.ZPX;
		}
		if(lowNibble==9||lowNibble==0xB) {		
			return (highNibble%2==0)?AddressingMode.IMM:AddressingMode.ABSY;
		}
		if(lowNibble==0xA||lowNibble==0x8) {
			return AddressingMode.NONE;
		}
		if(lowNibble==0xC) {
			if(highNibble == 0x6)
				return AddressingMode.IND;
			return (highNibble%2==0)?AddressingMode.ABS:AddressingMode.ABSX;
		}
		if(lowNibble==0xD) {
			return (highNibble%2==0)?AddressingMode.ABS:AddressingMode.ABSX;
		}
		if(lowNibble==0xE||lowNibble==0xF) {
			if(highNibble<=0x8||highNibble>=0xC)
				return (highNibble%2==0)?AddressingMode.ABS:AddressingMode.ABSX;
			return (highNibble%2==0)?AddressingMode.ABS:AddressingMode.ABSY;
		}		
		
		return AddressingMode.NONE;
	}

	private static AddressingMode[] initOpcodeAddressingMode() {
		AddressingMode[] array = new AddressingMode[0xFF];
		for(int i = 0; i < 255; i++) {
			array[i] = computeAddressingMode(i);
		}
		return array;
	}
	
	private static AddressingMode getAddressingMode(int opcode) {
		return opcodeAddressingMode[opcode];
	}
	
	private int getOperand(AddressingMode mode) {
		if(mode == AddressingMode.NONE)
			return 0;
		
		int operand = memory[regs.PC++];
		switch(mode) {
		case IMM:
			operand = memory[regs.PC++];
			return operand;
		case ZP:
			operand = memory[regs.PC++];
			return zeroPageAddressing(operand);
		case ZPX:
			operand = memory[regs.PC++];
			return zeroPageAddressingX(operand);
		case ZPY:
			operand = memory[regs.PC++];
			return zeroPageAddressingY(operand);
		case IZX:
			operand = memory[regs.PC++];
			return indAddressingX(operand);
		case IZY:
			operand = memory[regs.PC++];
			return indAddressingY(operand);
		case ABS:
			operand = readInt(regs.PC);
			regs.PC += 2;
			return absAddressing(operand);
		case ABSX:
			operand = readInt(regs.PC);
			regs.PC += 2;
			return absAddressingX(operand);
		case ABSY:
			operand = readInt(regs.PC);
			regs.PC += 2;
			return absAddressingY(operand);
		case IND:
			operand = readInt(regs.PC);
			regs.PC += 2;
			return readInt(operand);
		case REL:
			operand = memory[regs.PC++];
			return operand;
		default:
			return 0;
		}
	}
	
	public void run() {
		for(;;) {
			int opcode = memory[regs.PC++];
			AddressingMode mode = getAddressingMode(opcode);			
			int operand = getOperand(mode);
			Instruction inst = instList[opcode];
			if(inst != null) {
				ticks += inst.execute(operand);				
			}
			else
				System.err.printf("Unknown opcode %x\n", opcode);
		}
	}	
}
