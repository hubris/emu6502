import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Vector;

public class Emu6502 {

	private static void loadO65InMemory(String objName, Cpu6502 cpu) {		
		try {
			FileInputStream in = new FileInputStream(objName);
			int c;
			int addr = 0;
			while ((c = in.read()) != -1) {
				cpu.writeByte(addr++, c);
			}
			in.close();
		} catch (IOException e) {
			e.printStackTrace();
		}		
	}
	
	public static void main(String[] args) {
		if(args.length != 1)
			System.exit(0);
		
		Cpu6502 cpu = new Cpu6502(0);
		loadO65InMemory(args[0], cpu);
		cpu.run();		
	}

}
