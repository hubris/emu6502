import java.lang.String;
import java.lang.annotation.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class Cpu6502 {
	@Retention(RetentionPolicy.RUNTIME)
	@Target(value=ElementType.TYPE)
	public @interface InstructionClass {}
	
	private enum AddressingMode { IMM, ZP, ZPX, ZPY, IZX, IZY, ABS, ABSX, ABSY, IND, REL, ACC, NONE };
	private enum ProcessorFlags {
	  NEGATIVE(128),
	  OVERFLOW(64),
	  UNUSED(32),
	  BREAK(16),
	  DECIMAL(8),
	  INTERRUPT(4),
	  ZERO(2),
	  CARRY(1);
	  public final int value;
	  ProcessorFlags(int val) { value = val;}
	};

	
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

		/**
		 * @param flag set processor control flags
		 */
		public void setFlags(int flag) {
			carry = (flag&ProcessorFlags.CARRY.value)==1;
			zero = (flag&ProcessorFlags.ZERO.value)==1;
			interrupt = (flag&ProcessorFlags.INTERRUPT.value)==1;
			decimal = (flag&ProcessorFlags.DECIMAL.value)==1;
			brk = (flag&ProcessorFlags.BREAK.value)==1;
			overflow = (flag&ProcessorFlags.OVERFLOW.value)==1;
			negative = (flag&ProcessorFlags.NEGATIVE.value)==1;						  
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
		
		/**
		 * Output result into memory or register according to addressing mode
		 * @param addr memory location to write 
		 * @param res result to write
		 */
		protected void outputResult(int addr, int res) {
			if(mode == AddressingMode.ACC)
				regs.A = res;
			else
				writeByte(addr, res);				
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
			int addr = operand;
			operand = convertOperand(operand);			
			int res = operand<<1;						
			regs.carry = (res&0x100)!=0;
			res &= 0xFF;
			outputResult(addr, res);
			updateNZ(res);
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

	class InstrCPX extends Instruction {
		public InstrCPX(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			int res = regs.X-operand;
			regs.carry = (regs.X >= operand);
			regs.zero = (regs.X == operand);
			regs.negative = (res&0x80) == 0;
			return numCycles;
		}
	}
	
	@InstructionClass class InstrCPXImm extends InstrCPX
	{
		public InstrCPXImm() {
			super("CPX", 0xE0, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrCPXZp extends InstrCPX
	{
		public InstrCPXZp() {
			super("CPX", 0xE4, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrCPXAbs extends InstrCPX
	{
		public InstrCPXAbs() {			
			super("CPX", 0xEC, 3, AddressingMode.ABS, 4, false);
		}
	}
	
	class InstrCPY extends Instruction {
		public InstrCPY(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			int res = regs.Y-operand;
			regs.carry = (regs.Y >= operand);
			regs.zero = (regs.Y == operand);
			regs.negative = (res&0x80) == 0;
			return numCycles;
		}
	}
	
	@InstructionClass class InstrCPYImm extends InstrCPY
	{
		public InstrCPYImm() {
			super("CPY", 0xC0, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrCPYZp extends InstrCPY
	{
		public InstrCPYZp() {
			super("CPY", 0xC4, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrCPYAbs extends InstrCPY
	{
		public InstrCPYAbs() {			
			super("CPY", 0xCC, 3, AddressingMode.ABS, 4, false);
		}
	}
	
	class InstrDEC extends Instruction {
		public InstrDEC(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
				
		public int execute(int operand) {			
			int res = (memory[operand]-1)&0xFF;
			memory[operand] = res;
			updateNZ(res);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrDECZp extends InstrDEC
	{
		public InstrDECZp() {
			super("DEC", 0xC6, 2, AddressingMode.ZP, 5, false);
		}
	}

	@InstructionClass class InstrDECZpX extends InstrDEC
	{
		public InstrDECZpX() {			
			super("DEC", 0xD6, 2, AddressingMode.ZPX, 6, false);
		}
	}

	@InstructionClass class InstrDECAbs extends InstrDEC
	{
		public InstrDECAbs() {			
			super("DEC", 0xCE, 3, AddressingMode.ABS, 6, false);
		}
	}

	@InstructionClass class InstrDECAbsX extends InstrDEC
	{
		public InstrDECAbsX() {			
			super("DEC", 0xDE, 3, AddressingMode.ABSX, 7, true);
		}
	}

	@InstructionClass class InstrDEX extends Instruction {
		public InstrDEX() {			
			super("DEX", 0xCA, 1, AddressingMode.NONE, 2, true);
		}
				
		public int execute(int operand) {			
			regs.X = (regs.X-1)&0xFF;			
			updateNZ(regs.X);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrDEY extends Instruction {
		public InstrDEY() {			
			super("DEY", 0x88, 1, AddressingMode.NONE, 2, true);
		}
				
		public int execute(int operand) {			
			regs.Y = (regs.Y-1)&0xFF;			
			updateNZ(regs.Y);
			return numCycles;
		}
	}
	
	class InstrEOR extends Instruction {
		public InstrEOR(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			regs.A = regs.A^operand;
			updateNZ(regs.A);			
			return numCycles;
		}
	}
	
	@InstructionClass class InstrEORImm extends InstrEOR
	{
		public InstrEORImm() {
			super("EOR", 0x49, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrEORZp extends InstrEOR
	{
		public InstrEORZp() {
			super("EOR", 0x45, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrEORZpX extends InstrEOR
	{
		public InstrEORZpX() {			
			super("EOR", 0x55, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrEORAbs extends InstrEOR
	{
		public InstrEORAbs() {			
			super("EOR", 0x4D, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrEORAbsX extends InstrEOR
	{
		public InstrEORAbsX() {			
			super("EOR", 0x5D, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrEORAbsY extends InstrEOR
	{
		public InstrEORAbsY() {			
			super("EOR", 0x59, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrEORIndX extends InstrEOR
	{
		public InstrEORIndX() {			
			super("EOR", 0x41, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrEORIndY extends InstrEOR
	{
		public InstrEORIndY() {			
			super("EOR", 0x51, 2, AddressingMode.IZY, 5, true);
		}
	}
	
	class InstrINC extends Instruction {
		public InstrINC(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("INC", opcode, length, mode, numCycles, extraCycle);
		}
				
		public int execute(int operand) {			
			int res = (memory[operand]+1)&0xFF;
			memory[operand] = res;
			updateNZ(res);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrINCZp extends InstrINC
	{
		public InstrINCZp() {
			super(0xE6, 2, AddressingMode.ZP, 5, false);
		}
	}

	@InstructionClass class InstrINCZpX extends InstrINC
	{
		public InstrINCZpX() {			
			super(0xF6, 2, AddressingMode.ZPX, 6, false);
		}
	}

	@InstructionClass class InstrINCAbs extends InstrINC
	{
		public InstrINCAbs() {			
			super(0xEE, 3, AddressingMode.ABS, 6, false);
		}
	}

	@InstructionClass class InstrINCAbsX extends InstrINC
	{
		public InstrINCAbsX() {			
			super(0xFE, 3, AddressingMode.ABSX, 7, true);
		}
	}
	
	@InstructionClass class InstrINX extends Instruction {
		public InstrINX() {			
			super("INX", 0xE8, 1, AddressingMode.NONE, 2, true);
		}
				
		public int execute(int operand) {			
			regs.X = (regs.X+1)&0xFF;			
			updateNZ(regs.X);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrINY extends Instruction {
		public InstrINY() {			
			super("INY", 0xC8, 1, AddressingMode.NONE, 2, true);
		}
				
		public int execute(int operand) {			
			regs.Y = (regs.Y+1)&0xFF;			
			updateNZ(regs.Y);
			return numCycles;
		}
	}
	
	class InstrJMP extends Instruction {
		public InstrJMP(int opcode, int length, AddressingMode mode, 
				int numCycles) {
			super("JMP", opcode, length, mode, numCycles, false);
		}
				
		public int execute(int operand) {
			regs.PC = operand;
			return numCycles;
		}
	}
	
	@InstructionClass class InstrJMPAbs extends InstrJMP
	{
		public InstrJMPAbs() {			
			super(0x4C, 3, AddressingMode.ABS, 3);
		}
	}
	@InstructionClass class InstrJMPInd extends InstrJMP
	{
		public InstrJMPInd() {			
			super(0x6C, 3, AddressingMode.IND, 5);
		}
	}

	@InstructionClass class InstrJSR extends Instruction {
		public InstrJSR() {			
			super("JSR", 0x20, 3, AddressingMode.ABS, 6, false);
		}
				
		public int execute(int operand) {			
			pushInt((regs.PC-1)&0xFFFF);
			regs.PC = operand;
			return numCycles;
		}
	}
	
	@InstructionClass class InstrRTS extends Instruction {
		public InstrRTS() {			
			super("RTS", 0x60, 1, AddressingMode.NONE, 6, false);
		}
				
		public int execute(int operand) {
			int newPC = popInt()+1;			
			regs.PC = newPC;
			return numCycles;
		}
	}
	
	class InstrLDA extends Instruction {
		public InstrLDA(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("LDA", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			regs.A = operand;
			updateNZ(regs.A);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrLDAImm extends InstrLDA
	{
		public InstrLDAImm() {
			super(0xA9, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrLDAZp extends InstrLDA
	{
		public InstrLDAZp() {
			super(0xA5, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrLDAZpX extends InstrLDA
	{
		public InstrLDAZpX() {			
			super(0xB5, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrLDAAbs extends InstrLDA
	{
		public InstrLDAAbs() {			
			super(0xAD, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrLDAAbsX extends InstrLDA
	{
		public InstrLDAAbsX() {			
			super(0xBD, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrLDAAbsY extends InstrLDA
	{
		public InstrLDAAbsY() {			
			super(0xB9, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrLDAIndX extends InstrLDA
	{
		public InstrLDAIndX() {			
			super(0xA1, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrLDAIndY extends InstrLDA
	{
		public InstrLDAIndY() {			
			super(0xB1, 2, AddressingMode.IZY, 5, true);
		}
	}
	
	class InstrLDX extends Instruction {
		public InstrLDX(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("LDX", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			regs.X = operand;
			updateNZ(regs.X);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrLDXImm extends InstrLDX
	{
		public InstrLDXImm() {
			super(0xA2, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrLDXZp extends InstrLDX
	{
		public InstrLDXZp() {
			super(0xA6, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrLDXZpY extends InstrLDX
	{
		public InstrLDXZpY() {
			super(0xB6, 2, AddressingMode.ZPY, 4, false);
		}
	}

	@InstructionClass class InstrLDXAbs extends InstrLDX
	{
		public InstrLDXAbs() {			
			super(0xAE, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrLDXAbsY extends InstrLDX
	{
		public InstrLDXAbsY() {			
			super(0xBE, 3, AddressingMode.ABSY, 4, true);
		}
	}	
	
	class InstrLDY extends Instruction {
		public InstrLDY(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("LDY", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			regs.Y = operand;
			updateNZ(regs.Y);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrLDYImm extends InstrLDY
	{
		public InstrLDYImm() {
			super(0xA0, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrLDYZp extends InstrLDY
	{
		public InstrLDYZp() {
			super(0xA4, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrLDYZpX extends InstrLDY
	{
		public InstrLDYZpX() {
			super(0xB4, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrLDYAbs extends InstrLDY
	{
		public InstrLDYAbs() {			
			super(0xAC, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrLDYAbsX extends InstrLDY
	{
		public InstrLDYAbsX() {			
			super(0xBC, 3, AddressingMode.ABSY, 4, true);
		}
	}	
	
	class InstrLSR extends Instruction {
		public InstrLSR(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("LSR", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			int addr = operand;
			operand = convertOperand(operand);			
			int res = operand>>1;						
			regs.carry = (res&0x100)!=0;
			res &= 0xFF;
			outputResult(addr, res);
			updateNZ(res);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrLSRAcc extends InstrLSR
	{
		public InstrLSRAcc() {
			super(0x4A, 1, AddressingMode.ACC, 2, false);
		}	
	}
	
	@InstructionClass class InstrLSRZp extends InstrLSR
	{
		public InstrLSRZp() {
			super(0x46, 2, AddressingMode.ZP, 5, false);
		}
	}

	@InstructionClass class InstrLSRZpX extends InstrLSR
	{
		public InstrLSRZpX() {
			super(0x56, 2, AddressingMode.ZPX, 6, false);
		}
	}

	@InstructionClass class InstrLSRAbs extends InstrLSR
	{
		public InstrLSRAbs() {			
			super(0x4E, 3, AddressingMode.ABS, 6, false);
		}
	}

	@InstructionClass class InstrLSRAbsX extends InstrLSR
	{
		public InstrLSRAbsX() {			
			super(0x5E, 3, AddressingMode.ABSX, 7, false);
		}
	}	
	
	@InstructionClass class InstrNOP extends Instruction {
		public InstrNOP() {			
			super("NOP", 0xEA, 1, AddressingMode.NONE, 2, false);
		}
		
		public int execute(int operand) {
			return numCycles;
		}
	}
	
	class InstrORA extends Instruction {
		public InstrORA(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("ORA", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			regs.A |= operand;
			updateNZ(regs.A);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrORAImm extends InstrORA
	{
		public InstrORAImm() {
			super("ORA", 0x09, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrORAZp extends InstrORA
	{
		public InstrORAZp() {
			super("ORA", 0x05, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrORAZpX extends InstrORA
	{
		public InstrORAZpX() {			
			super("ORA", 0x15, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrORAAbs extends InstrORA
	{
		public InstrORAAbs() {			
			super("ORA", 0x0D, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrORAAbsX extends InstrORA
	{
		public InstrORAAbsX() {			
			super("ORA", 0x1D, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrORAAbsY extends InstrORA
	{
		public InstrORAAbsY() {			
			super("ORA", 0x19, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrORAIndX extends InstrORA
	{
		public InstrORAIndX() {			
			super("ORA", 0x01, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrORAIndY extends InstrORA
	{
		public InstrORAIndY() {			
			super("ORA", 0x11, 2, AddressingMode.IZY, 5, true);
		}
	}
	
	@InstructionClass class InstrPHA extends Instruction {
		public InstrPHA() {			
			super("PHA", 0x48, 1, AddressingMode.NONE, 3, false);
		}
		
		public int execute(int operand) {
			pushByte(regs.A);
			return numCycles;
		}
	}

	@InstructionClass class InstrPHP extends Instruction {
		public InstrPHP() {			
			super("PHP", 0x08, 1, AddressingMode.NONE, 3, false);
		}
		
		public int execute(int operand) {
			pushByte(regs.getFlags());
			return numCycles;
		}
	}
	
	@InstructionClass class InstrPLA extends Instruction {
		public InstrPLA() {			
			super("PLA", 0x68, 1, AddressingMode.NONE, 4, false);
		}
		
		public int execute(int operand) {
			regs.A = popByte();
			updateNZ(regs.A);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrPLP extends Instruction {
		public InstrPLP() {			
			super("PLP", 0x28, 1, AddressingMode.NONE, 4, false);
		}
		
		public int execute(int operand) {
			regs.setFlags(popByte());			
			return numCycles;
		}
	}
	
	class InstrROL extends Instruction {
		public InstrROL(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("ROL", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			int addr = operand;
			operand = convertOperand(operand);			
			int res = (operand<<1)|(regs.carry?0:1);			
			regs.carry = (res&0x100)!=0;
			res &= 0xFF;
			outputResult(addr, res);
			updateNZ(res);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrROLAcc extends InstrROL
	{
		public InstrROLAcc() {
			super(0x2A, 1, AddressingMode.ACC, 2, false);
		}	
	}
	
	@InstructionClass class InstrROLZp extends InstrROL
	{
		public InstrROLZp() {
			super(0x26, 2, AddressingMode.ZP, 5, false);
		}
	}

	@InstructionClass class InstrROLZpX extends InstrROL
	{
		public InstrROLZpX() {
			super(0x36, 2, AddressingMode.ZPX, 6, false);
		}
	}

	@InstructionClass class InstrROLAbs extends InstrROL
	{
		public InstrROLAbs() {			
			super(0x2E, 3, AddressingMode.ABS, 6, false);
		}
	}

	@InstructionClass class InstrROLAbsX extends InstrROL
	{
		public InstrROLAbsX() {			
			super(0x3E, 3, AddressingMode.ABSX, 7, false);
		}
	}	
	
	class InstrROR extends Instruction {
		public InstrROR(int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super("ROR", opcode, length, mode, numCycles, extraCycle);
		}
		
		public int execute(int operand) {
			int addr = operand;
			operand = convertOperand(operand);			
			int res = (operand>>1)|((regs.carry?0:1)<<7);			
			regs.carry = (operand&1)!=0;
			res &= 0xFF;
			outputResult(addr, res);
			updateNZ(res);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrRORAcc extends InstrROR
	{
		public InstrRORAcc() {
			super(0x6A, 1, AddressingMode.ACC, 2, false);
		}	
	}
	
	@InstructionClass class InstrRORZp extends InstrROR
	{
		public InstrRORZp() {
			super(0x66, 2, AddressingMode.ZP, 5, false);
		}
	}

	@InstructionClass class InstrRORZpX extends InstrROR
	{
		public InstrRORZpX() {
			super(0x76, 2, AddressingMode.ZPX, 6, false);
		}
	}

	@InstructionClass class InstrRORAbs extends InstrROR
	{
		public InstrRORAbs() {			
			super(0x6E, 3, AddressingMode.ABS, 6, false);
		}
	}

	@InstructionClass class InstrRORAbsX extends InstrROR
	{
		public InstrRORAbsX() {			
			super(0x7E, 3, AddressingMode.ABSX, 7, false);
		}
	}	
	
	@InstructionClass class InstrRTI extends Instruction {
		public InstrRTI() {			
			super("RTI", 0x40, 1, AddressingMode.NONE, 6, false);
		}
				
		public int execute(int operand) {
			regs.setFlags(popByte());						
			regs.PC = popInt();
			return numCycles;
		}
	}

	//TODO: Probably bugged
	class InstrSBC extends Instruction {
		public InstrSBC(String name, int opcode, int length, AddressingMode mode, 
				int numCycles, boolean extraCycle) {
			super(name, opcode, length, mode, numCycles, extraCycle);
		}

		private void bcdSub(int operand) {
			int carry = 1-(regs.carry?1:0);
			int a = Utils6502.unpackBcd(operand);
			int b = Utils6502.unpackBcd(regs.A);			
			int res = a-b-carry;			
			regs.A = res%100;
			regs.carry = (res==0||res>0);
			regs.zero = (regs.A == 0);	
			regs.overflow = false;
			regs.negative = (res<0);
			regs.A = Utils6502.packBcd(res);		 
		}
		
		private void binarySub(int operand) {
			int carry = 1-(regs.carry?1:0);
			int res = regs.A-operand-carry;
			regs.A = res&255;
			regs.carry = (res&0x100)==0;
			regs.zero = (regs.A == 0);	
			regs.overflow = (res>127 || res<-128);
			regs.negative = (regs.A&0x80) != 0;
		}
		
		public int execute(int operand) {
			operand = convertOperand(operand);
			if(regs.decimal)
				bcdSub(operand);				
			else
				binarySub(operand);
			return numCycles;
		}
	}
	
	@InstructionClass class InstrSBCImm extends InstrSBC
	{
		public InstrSBCImm() {
			super("SBC", 0xE9, 2, AddressingMode.IMM, 2, false);
		}	
	}

	@InstructionClass class InstrSBCZp extends InstrSBC
	{
		public InstrSBCZp() {
			super("SBC", 0xE5, 2, AddressingMode.ZP, 3, false);
		}
	}

	@InstructionClass class InstrSBCZpX extends InstrSBC
	{
		public InstrSBCZpX() {			
			super("SBC", 0xF5, 2, AddressingMode.ZPX, 4, false);
		}
	}

	@InstructionClass class InstrSBCAbs extends InstrSBC
	{
		public InstrSBCAbs() {			
			super("SBC", 0xED, 3, AddressingMode.ABS, 4, false);
		}
	}

	@InstructionClass class InstrSBCAbsX extends InstrSBC
	{
		public InstrSBCAbsX() {			
			super("SBC", 0xFD, 3, AddressingMode.ABSX, 4, true);
		}
	}

	@InstructionClass class InstrSBCAbsY extends InstrSBC
	{
		public InstrSBCAbsY() {			
			super("SBC", 0xF9, 3, AddressingMode.ABSY, 4, true);
		}
	}
	
	@InstructionClass class InstrSBCIndX extends InstrSBC
	{
		public InstrSBCIndX() {			
			super("SBC", 0xE1, 2, AddressingMode.IZX, 6, false);
		}
	}
	
	@InstructionClass class InstrSBCIndY extends InstrSBC
	{
		public InstrSBCIndY() {			
			super("SBC", 0xF1, 2, AddressingMode.IZY, 5, true);
		}
	}
	
	class InstrSTA extends Instruction {
		public InstrSTA(int opcode, int length, AddressingMode mode, 
				int numCycles) {
			super("STA", opcode, length, mode, numCycles, false);
		}
	
		public int execute(int operand) {
			writeByte(operand, regs.A);			
			return numCycles;
		}
	}
	
	@InstructionClass class InstrSTAZp extends InstrSTA
	{
		public InstrSTAZp() {
			super(0x85, 2, AddressingMode.ZP, 3);
		}
	}

	@InstructionClass class InstrSTAZpX extends InstrSTA
	{
		public InstrSTAZpX() {			
			super(0x95, 2, AddressingMode.ZPX, 4);
		}
	}

	@InstructionClass class InstrSTAAbs extends InstrSTA
	{
		public InstrSTAAbs() {			
			super(0x8D, 3, AddressingMode.ABS, 4);
		}
	}

	@InstructionClass class InstrSTAAbsX extends InstrSTA
	{
		public InstrSTAAbsX() {			
			super(0x9D, 3, AddressingMode.ABSX, 5);
		}
	}

	@InstructionClass class InstrSTAAbsY extends InstrSTA
	{
		public InstrSTAAbsY() {			
			super(0x99, 3, AddressingMode.ABSY, 5);
		}
	}
	
	@InstructionClass class InstrSTAIndX extends InstrSTA
	{
		public InstrSTAIndX() {			
			super(0x81, 2, AddressingMode.IZX, 6);
		}
	}
	
	@InstructionClass class InstrSTAIndY extends InstrSTA
	{
		public InstrSTAIndY() {			
			super(0x91, 2, AddressingMode.IZY, 5);
		}
	}
	
	class InstrSTX extends Instruction {
		public InstrSTX(int opcode, int length, AddressingMode mode, 
				int numCycles) {
			super("STX", opcode, length, mode, numCycles, false);
		}
	
		public int execute(int operand) {
			writeByte(operand, regs.X);			
			return numCycles;
		}
	}
	
	@InstructionClass class InstrSTXZp extends InstrSTX
	{
		public InstrSTXZp() {
			super(0x86, 2, AddressingMode.ZP, 3);
		}
	}

	@InstructionClass class InstrSTXZpY extends InstrSTX
	{
		public InstrSTXZpY() {			
			super(0x96, 2, AddressingMode.ZPY, 4);
		}
	}

	@InstructionClass class InstrSTXAbs extends InstrSTX
	{
		public InstrSTXAbs() {			
			super(0x8E, 3, AddressingMode.ABS, 4);
		}
	}
	
	class InstrSTY extends Instruction {
		public InstrSTY(int opcode, int length, AddressingMode mode, 
				int numCycles) {
			super("STY", opcode, length, mode, numCycles, false);
		}
	
		public int execute(int operand) {
			writeByte(operand, regs.Y);			
			return numCycles;
		}
	}
	
	@InstructionClass class InstrSTYZp extends InstrSTY
	{
		public InstrSTYZp() {
			super(0x84, 2, AddressingMode.ZP, 3);
		}
	}

	@InstructionClass class InstrSTYZpX extends InstrSTY
	{
		public InstrSTYZpX() {			
			super(0x94, 2, AddressingMode.ZPX, 4);
		}
	}

	@InstructionClass class InstrSTYAbs extends InstrSTY
	{
		public InstrSTYAbs() {			
			super(0x8C, 3, AddressingMode.ABS, 4);
		}
	}
	
	@InstructionClass class InstrTAX extends Instruction {
		public InstrTAX() {			
			super("TAX", 0xAA, 1, AddressingMode.NONE, 2, false);
		}
	
		public int execute(int operand) {
			regs.X = regs.A;	
			updateNZ(regs.X);
			return numCycles;
		}
	}

	@InstructionClass class InstrTSX extends Instruction {
		public InstrTSX() {			
			super("TSX", 0xBA, 1, AddressingMode.NONE, 2, false);
		}
	
		public int execute(int operand) {
			regs.X = regs.SP;		
			updateNZ(regs.X);
			return numCycles;
		}
	}

	@InstructionClass class InstrTXA extends Instruction {
		public InstrTXA() {			
			super("TXA", 0x8A, 1, AddressingMode.NONE, 2, false);
		}
	
		public int execute(int operand) {
			regs.A = regs.X;		
			updateNZ(regs.A);
			return numCycles;
		}
	}

	@InstructionClass class InstrTXS extends Instruction {
		public InstrTXS() {			
			super("TXS", 0x9A, 1, AddressingMode.NONE, 2, false);
		}
	
		public int execute(int operand) {
			regs.SP = regs.X;			
			return numCycles;
		}
	}
	
	@InstructionClass class InstrTYA extends Instruction {
		public InstrTYA() {			
			super("TYA", 0x98, 1, AddressingMode.NONE, 2, false);
		}
	
		public int execute(int operand) {
			regs.A = regs.Y;		
			updateNZ(regs.A);
			return numCycles;
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
		} catch (Exception e) {
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

	private int readByte(int addr) {
		return memory[addr];
	}

	private int readInt(int addr)
	{
		return (readByte(addr+1)<<8)|readByte(addr);			
	}

	public int writeByte(int addr, int val)
	{
		return memory[addr] = val;
	}

	private int readIntJmpBug(int addr)
	{
		if((addr&0xFF) != 0xFF)
			return readInt(addr);
		int low = readByte(addr);
		int high = readByte(addr&0xFF00);
		return (high<<8)|low;			
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
		int high = (val>>8)&0xFF;
		int low = val&0xFF;
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
	
	private int getOperand(AddressingMode mode, boolean emulAddressingBug) {
		if(mode == AddressingMode.NONE)
			return 0;
		
		int operand;
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
			if(emulAddressingBug) 
				return readIntJmpBug(operand);
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
			Instruction inst = instList[opcode];
			AddressingMode mode = getAddressingMode(opcode);
			boolean emulAddressingBug = (inst.name == "JMP");
			int operand = getOperand(mode, emulAddressingBug);			
			if(inst != null) {
				ticks += inst.execute(operand);				
			}
			else
				System.err.printf("Unknown opcode %x\n", opcode);
		}
	}	
}
