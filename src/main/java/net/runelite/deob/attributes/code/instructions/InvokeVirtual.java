package net.runelite.deob.attributes.code.instructions;

import net.runelite.deob.ClassFile;
import net.runelite.deob.ClassGroup;
import net.runelite.deob.attributes.code.Instruction;
import net.runelite.deob.attributes.code.InstructionType;
import net.runelite.deob.attributes.code.Instructions;
import net.runelite.deob.attributes.code.instruction.types.InvokeInstruction;
import net.runelite.deob.execution.Frame;
import net.runelite.deob.execution.InstructionContext;
import net.runelite.deob.execution.Stack;
import net.runelite.deob.execution.StackContext;
import net.runelite.deob.execution.Type;
import net.runelite.deob.pool.Method;
import net.runelite.deob.pool.NameAndType;
import net.runelite.deob.pool.PoolEntry;
import net.runelite.deob.signature.Signature;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.deob.attributes.code.instruction.types.MappableInstruction;
import net.runelite.deob.deobfuscators.rename.MappingExecutorUtil;
import net.runelite.deob.deobfuscators.rename.ParallelExecutorMapping;
import net.runelite.deob.execution.Execution;
import net.runelite.deob.execution.Value;

public class InvokeVirtual extends Instruction implements InvokeInstruction
{
	private Method method;
	private List<net.runelite.deob.Method> myMethods;

	public InvokeVirtual(Instructions instructions, InstructionType type, int pc)
	{
		super(instructions, type, pc);
	}
	
	@Override
	public String toString()
	{
		return "invokevirtual " + method + " in " + this.getInstructions().getCode().getAttributes().getMethod();
	}
	
	@Override
	public void load(DataInputStream is) throws IOException
	{
		method = this.getPool().getMethod(is.readUnsignedShort());
		length += 2;
	}
	
	@Override
	public void write(DataOutputStream out) throws IOException
	{
		super.write(out);
		out.writeShort(this.getPool().make(method));
	}

	@Override
	public void execute(Frame frame)
	{
		InstructionContext ins = new InstructionContext(this, frame);
		Stack stack = frame.getStack();
		
		int count = method.getNameAndType().getNumberOfArgs();
		
		for (int i = 0; i < count; ++i)
		{
			StackContext arg = stack.pop();
			ins.pop(arg);
		}
		
		StackContext object = stack.pop();
		ins.pop(object);
		
		if (!method.getNameAndType().isVoid())
		{
			StackContext ctx = new StackContext(ins,
				new Type(method.getNameAndType().getDescriptor().getReturnValue()).toStackType(),
				Value.NULL
			);
			stack.push(ctx);
			
			ins.push(ctx);
		}

		for (net.runelite.deob.Method method : getMethods())
		{
			ins.invoke(method);
			
			if (method.getCode() == null)
			{
				frame.getExecution().methods.add(method);
				continue;
			}
			
			// add possible method call to execution
			Execution execution = frame.getExecution();
			execution.invoke(ins, method);
		}
		
		frame.addInstructionContext(ins);
	}
	
	// find the possible methods this instruction might be invoking. we can't know for sure
	// which method is being invoked without tracking the types of objects in fields and when
	// passed in parameters/return values.
	@Override
	public List<net.runelite.deob.Method> getMethods()
	{
		return myMethods != null ? myMethods : Arrays.asList();
	}
	
	private void findMethodFromClass(List<net.runelite.deob.Method> list, ClassFile clazz)
	{
		net.runelite.deob.Method m = clazz.findMethodDeep(method.getNameAndType());
		if (m != null && !list.contains(m))
			list.add(m);
	
		for (ClassFile cf : clazz.getChildren())
			findMethodFromClass(list, cf);
	}
	
	@Override
	public void removeParameter(int idx)
	{
		net.runelite.deob.pool.Class clazz = method.getClassEntry();
		NameAndType nat = method.getNameAndType();
		
		// create new signature
		Signature sig = new Signature(nat.getDescriptor());
		sig.remove(idx);
		
		// create new method pool object
		method = new Method(clazz, new NameAndType(nat.getName(), sig));
	}
	
	@Override
	public PoolEntry getMethod()
	{
		return method;
	}
	
	@Override
	public void lookup()
	{
		ClassGroup group = this.getInstructions().getCode().getAttributes().getClassFile().getGroup();
		
		ClassFile otherClass = group.findClass(method.getClassEntry().getName());
		if (otherClass == null)
			return; // not our class
		
		// look up this method in this class and anything that inherits from it
		List<net.runelite.deob.Method> list = new ArrayList<>();
		findMethodFromClass(list, otherClass);
		myMethods = list;
	}
	
	@Override
	public void regeneratePool()
	{
		if (myMethods != null && !myMethods.isEmpty())
			method = myMethods.get(0).getPoolMethod(); // is this right?
	}
	
	@Override
	public void map(ParallelExecutorMapping mapping, InstructionContext ctx, InstructionContext other)
	{
		List<net.runelite.deob.Method> myMethods = this.getMethods(),
			otherMethods = ((InvokeVirtual) other.getInstruction()).getMethods();
		
		assert myMethods.size() == otherMethods.size();
		
		for (int i = 0; i < myMethods.size(); ++i)
		{
			net.runelite.deob.Method m1 = myMethods.get(i), m2 = otherMethods.get(i);

			assert m1.getMethods().getClassFile().getName().equals(m2.getMethods().getClassFile().getName());
			
			mapping.map(m1, m2);
		}
	}
	
	@Override
	public boolean isSame(InstructionContext thisIc, InstructionContext otherIc)
	{
		return thisIc.getInstruction().getClass() == otherIc.getInstruction().getClass();
	}
	
	@Override
	public boolean canMap()
	{
		return MappingExecutorUtil.isMappable(this);
	}
}
