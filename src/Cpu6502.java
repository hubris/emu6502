import java.io.Console;
import java.lang.String;
import java.lang.annotation.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class Cpu6502 {
	@Retention(RetentionPolicy.RUNTIME)
	@Target(value=ElementType.TYPE)
	public @interface InstructionClass {}
	
	private enum AddressingMode { IMM, ZP, ZPX, ZPY, IZX, IZY, ABS, ABSX, ABSY, IND, REL, NONE };
	
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
		protected int numCycles;
		protected boolean extraCycle;

		public Instruction(String name, int opcode, int length, int numCycles,
				           boolean extraCycle) {
			super();
			this.name = name;
			this.opcode = opcode;
			this.length = length;
			this.numCycles = numCycles;
			this.extraCycle = extraCycle;
		}		
		abstract public void execute();

		public int getOpcode() {
			return opcode;
		}
	}	

	abstract class InstrADC extends Instruction {
		public InstrADC(String name, int opcode, int length, int numCycles,
		           boolean extraCycle) {
			super(name, opcode, length, numCycles, extraCycle);
		}

		private void bcdAdd(int operand) {
			int carry = regs.carry?1:0;
			int a = Utils6502.unpackBcd(operand);
			int b = Utils6502.unpackBcd(regs.A);			
			int res = a+b+carry;
			regs.A = res%99;
			regs.carry = res>99	;
			regs.zero = (regs.A == 0);	
			regs.overflow = (res>99);
			regs.negative = (regs.A&128) != 0;
			regs.A = Utils6502.packBcd(res);		 
		}
		private void binaryAdd(int operand) {
			int carry = regs.carry?1:0;
			int res = regs.A+operand+carry;
			regs.A = res%255;
			regs.carry = res>255;
			regs.zero = (regs.A == 0);	
			regs.overflow = (res>127 || res<-128);
			regs.negative = (regs.A&128) != 0;
		}
		
		protected void execute(int operand) {			
			if(regs.decimal)
				bcdAdd(operand);				
			else
				binaryAdd(operand);
			regs.PC += length-1;			 
		}
	}
	
	@InstructionClass class InstrADCImm extends InstrADC
	{
		public InstrADCImm() {
			super("ADC", 0x69, 2, 2, false);
		}
		
		public void execute() {
			super.execute(memory[regs.PC]);
		}		
	}

	@InstructionClass class InstrADCZp extends InstrADC
	{
		public InstrADCZp() {
			super("ADC", 0x65, 2, 3, false);
		}
		
		public void execute() {					
			super.execute(zeroPageAddressing(memory[regs.PC]));
		}
	}

	@InstructionClass class InstrADCZpX extends InstrADC
	{
		public InstrADCZpX() {			
			super("ADC", 0x75, 2, 4, false);
		}
		
		public void execute() {
			super.execute(zeroPageAddressingX(memory[regs.PC]));
		}
	}

	@InstructionClass class InstrADCAbs extends InstrADC
	{
		public InstrADCAbs() {			
			super("ADC", 0x6D, 3, 4, false);
		}
		
		public void execute() {			
			int addr = readInt(regs.PC);			
			super.execute(memory[addr]);
		}
	}

	@InstructionClass class InstrADCAbsX extends InstrADC
	{
		public InstrADCAbsX() {			
			super("ADC", 0x7D, 3, 4, true);
		}
		
		public void execute() {			
			super.execute(absAddressingX(memory[regs.PC]));
		}
	}

	@InstructionClass class InstrADCAbsY extends InstrADC
	{
		public InstrADCAbsY() {			
			super("ADC", 0x79, 3, 4, true);
		}
		
		public void execute() {
			super.execute(absAddressingY(memory[regs.PC]));
		}
	}
	
	@InstructionClass class InstrADCIndX extends InstrADC
	{
		public InstrADCIndX() {			
			super("ADC", 0x61, 2, 6, false);
		}
		
		public void execute() {
			super.execute(indAddressingX(memory[regs.PC]));
		}
	}
	
	@InstructionClass class InstrADCIndY extends InstrADC
	{
		public InstrADCIndY() {			
			super("ADC", 0x71, 2, 5, true);
		}
		
		public void execute() {			
			super.execute(indAddressingY(memory[regs.PC]));
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
		regs.SP = STACK_MEMORY;
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
		return memory[addr];
	}

	private int zeroPageAddressingX(int addr) {		
		return memory[(addr+regs.X)%255];
	}

	private int zeroPageAddressingY(int addr) {		
		return memory[(addr+regs.Y)%255];
	}

	private int absAddressingX(int addr) {		
		return memory[addr+regs.X];
	}

	private int absAddressingY(int addr) {		
		return memory[addr+regs.Y];
	}

	private int indAddressingX(int addr) {		
		int zp = (addr+regs.X)%255;
		return memory[readInt(zp)];
	}

	private int indAddressingY(int addr) {		
		int absAddr = readInt(addr)+regs.Y;	
		return memory[absAddr];
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
	
	private static AddressingMode getAddresingMode(int opcode) {
		return opcodeAddressingMode[opcode];
	}
	
	public void run() {
		for(;;) {
			int opcode = memory[regs.PC++];
			Instruction inst = instList[opcode];
			if(inst != null)
				inst.execute();
			else
				System.err.printf("Unknown opcode %x\n", opcode);
		}
	}	
}
